import { useWebSocket } from '@vueuse/core';
import { getChatFailureCopy } from '@/constants/chat-failure';
import { request } from '@/service/request';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const NON_RETRYABLE_CLOSE_CODES = new Set([1002, 1003, 1007, 1008]);
  const WS_HEARTBEAT_PING = '__chat_ping__';
  const WS_HEARTBEAT_PONG = '__chat_pong__';
  // pm-020 断线提示分级：同一轮断线内轻提示只发一次；持续断线超过该时长升级为不自动消失的横幅
  const CONNECTION_ESCALATION_MS = 10_000;
  // pm-021 刷新恢复：选中会话 id 持久在 sessionStorage（标签页级），刷新后恢复选中
  const ACTIVE_SESSION_STORAGE_KEY = 'mindbase:chat:active-conversation';

  const conversationId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });
  const list = ref<Api.Chat.Message[]>([]);
  const sessions = ref<Api.Chat.ConversationSession[]>([]);
  const sessionsLoading = ref(false);
  const activeTab = ref<'active' | 'archived'>('active');

  const store = useAuthStore();

  const sessionId = ref<string>('');
  const allowReconnect = ref(true);
  const authFailureNotified = ref(false);
  const handshakeConfirmed = ref(false);
  const intentionalDisconnect = ref(false);
  const rateLimitUntil = ref<number | null>(null);
  const rateLimitRemainingSeconds = ref(0);
  let rateLimitTimer: ReturnType<typeof setInterval> | null = null;
  // pm-020：与 connectionStatus 同源的升级状态位——断线后由同一 onDisconnected/onConnected 状态机驱动，
  // 不另起重连计数机制（autoReconnect.retries 恒真导致 onFailed 不可达，分级提示改由本状态位承载）
  const connectionEscalated = ref(false);
  const disconnectNotified = ref(false);
  let escalationTimer: number | null = null;

  function clearConnectionEscalation() {
    if (escalationTimer !== null) {
      window.clearTimeout(escalationTimer);
      escalationTimer = null;
    }
    connectionEscalated.value = false;
    disconnectNotified.value = false;
  }

  function startConnectionEscalation() {
    // 自动重连每次失败都会再触发 onDisconnected：升级计时只按整轮断线起算一次，不随重试重置
    if (escalationTimer !== null || connectionEscalated.value) {
      return;
    }
    escalationTimer = window.setTimeout(() => {
      escalationTimer = null;
      connectionEscalated.value = true;
    }, CONNECTION_ESCALATION_MS);
  }

  function mapGenerationStatus(status?: Api.Chat.GenerationStatus): Api.Chat.Message['status'] {
    if (status === 'COMPLETED' || status === 'CANCELLED') {
      return 'finished';
    }
    if (status === 'FAILED') {
      return 'error';
    }
    if (status === 'STREAMING') {
      return 'loading';
    }
    return 'pending';
  }

  function findAssistantIndexByGenerationId(generationId?: string) {
    if (!generationId) {
      return -1;
    }
    for (let i = list.value.length - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'assistant' && item.generationId === generationId) {
        return i;
      }
    }
    return -1;
  }

  function getPendingGenerationId() {
    for (let i = list.value.length - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'assistant' && ['pending', 'loading'].includes(item.status || '') && item.generationId) {
        return item.generationId;
      }
    }
    return '';
  }

  function matchesUnboundPending(questionText: string) {
    const pending = list.value[list.value.length - 1];
    const question = list.value[list.value.length - 2];
    return (
      pending?.role === 'assistant' &&
      !pending.generationId &&
      ['pending', 'loading'].includes(pending.status || '') &&
      question?.role === 'user' &&
      question.content === questionText
    );
  }

  function upsertGenerationSnapshot(snapshot: Api.Chat.GenerationSnapshot | null) {
    if (!snapshot) {
      return;
    }
    if (conversationId.value && snapshot.conversationId !== conversationId.value) return;
    conversationId.value = snapshot.conversationId || conversationId.value;

    let assistantIndex = findAssistantIndexByGenerationId(snapshot.generationId);
    if (assistantIndex < 0 && matchesUnboundPending(snapshot.question)) {
      assistantIndex = list.value.length - 1;
    }
    const nextStatus = mapGenerationStatus(snapshot.status);

    if (assistantIndex >= 0) {
      const assistant = list.value[assistantIndex];
      // FAILED 快照正文兜底取 chat-failure 字典话术（pm-015）：原始异常/技术串不再兜底为消息正文
      const failureMessage = snapshot.status === 'FAILED' ? getChatFailureCopy(snapshot.errorCategory).message : '';
      assistant.content = snapshot.content || assistant.content || failureMessage;
      assistant.status = nextStatus;
      assistant.generationId = snapshot.generationId;
      assistant.conversationId = snapshot.conversationId;
      assistant.timestamp ||= snapshot.updatedAt;
      // FAILED 快照：带上错误类别码，错误卡片按类别分层文案并可重试，不再是死消息（pm-001）
      if (snapshot.errorCategory) {
        assistant.errorCategory = snapshot.errorCategory;
      }
      if (snapshot.referenceMappings && Object.keys(snapshot.referenceMappings).length > 0) {
        assistant.referenceMappings = snapshot.referenceMappings;
      }
      return;
    }

    list.value.push({
      role: 'user',
      content: snapshot.question,
      conversationId: snapshot.conversationId,
      generationId: snapshot.generationId,
      timestamp: snapshot.createdAt
    });
    list.value.push({
      role: 'assistant',
      content: snapshot.content || '',
      status: nextStatus,
      conversationId: snapshot.conversationId,
      generationId: snapshot.generationId,
      timestamp: snapshot.updatedAt,
      errorCategory: snapshot.errorCategory || undefined,
      referenceMappings: snapshot.referenceMappings
    });
  }

  async function fetchGenerationSnapshot(generationId: string) {
    if (!generationId) {
      return null;
    }
    const { error, data } = await request<Api.Chat.GenerationSnapshot | null>({
      url: `chat/generation/${generationId}`
    });
    if (error) {
      return null;
    }
    return data || null;
  }

  async function fetchActiveGenerationSnapshot() {
    const { error, data } = await request<Api.Chat.GenerationSnapshot | null>({
      url: 'chat/active-generation'
    });
    if (error) {
      return null;
    }
    return data || null;
  }

  async function syncGenerationAfterReconnect() {
    const targetConversationId = conversationId.value;
    const pendingGenerationId = getPendingGenerationId();
    const snapshot = pendingGenerationId
      ? await fetchGenerationSnapshot(pendingGenerationId)
      : await fetchActiveGenerationSnapshot();
    if (conversationId.value !== targetConversationId || !snapshot) return;
    if (targetConversationId && snapshot.conversationId !== targetConversationId) return;
    upsertGenerationSnapshot(snapshot);
    if (snapshot.status === 'STREAMING') resumeGeneration(snapshot.generationId);
  }

  // ---- Session management ----

  async function loadSessions() {
    sessionsLoading.value = true;
    const { error, data } = await request<Api.Chat.ConversationSession[]>({
      url: 'users/conversations'
    });
    if (!error && data) {
      sessions.value = data;
      if (!conversationId.value) {
        // pm-021：优先恢复本标签页刷新前正在浏览的会话；该会话已不存在（归档/删除）则回欢迎区
        const storedId = sessionStorage.getItem(ACTIVE_SESSION_STORAGE_KEY);
        if (storedId) {
          const stored = data.find(s => s.conversationId === storedId && s.status === 'ACTIVE');
          if (stored) {
            await switchSession(storedId);
          } else {
            sessionStorage.removeItem(ACTIVE_SESSION_STORAGE_KEY);
          }
        } else {
          // Auto-select first active session if none is active
          const firstActive = data.find(s => s.status === 'ACTIVE');
          if (firstActive) {
            await switchSession(firstActive.conversationId);
          }
        }
      }
    }
    sessionsLoading.value = false;
  }

  async function createNewSession() {
    const { error, data } = await request<Api.Chat.ConversationSession>({
      url: 'users/conversations',
      method: 'POST'
    });
    if (!error && data) {
      conversationId.value = data.conversationId;
      list.value = [];
      sessionStorage.setItem(ACTIVE_SESSION_STORAGE_KEY, data.conversationId);
      await loadSessions();
    }
    return !error;
  }

  async function switchSession(targetConversationId: string) {
    if (targetConversationId === conversationId.value) {
      return;
    }
    const { error } = await request({
      url: `users/conversations/${targetConversationId}/switch`,
      method: 'PUT'
    });
    if (error) {
      return;
    }
    conversationId.value = targetConversationId;
    list.value = [];
    sessionStorage.setItem(ACTIVE_SESSION_STORAGE_KEY, targetConversationId);
    await loadMessages(targetConversationId);
  }

  async function loadMessages(targetConversationId?: string) {
    const cid = targetConversationId || conversationId.value;
    if (!cid) {
      return;
    }
    const messagesBeforeRequest = [...list.value];
    const { error, data } = await request<Api.Chat.Message[]>({
      url: 'users/conversation',
      params: { conversationId: cid }
    });
    if (
      !error &&
      data &&
      cid === conversationId.value &&
      list.value.length === messagesBeforeRequest.length &&
      list.value.every((message, index) => message === messagesBeforeRequest[index]) &&
      !list.value.some(message => ['pending', 'loading'].includes(message.status || ''))
    ) {
      list.value = data;
    }
  }

  async function archiveSession(targetConversationId: string) {
    const { error } = await request({
      url: `users/conversations/${targetConversationId}/archive`,
      method: 'PUT'
    });
    if (!error) {
      await loadSessions();
      if (targetConversationId === conversationId.value) {
        list.value = [];
        conversationId.value = '';
        sessionStorage.removeItem(ACTIVE_SESSION_STORAGE_KEY);
      }
    }
  }

  async function unarchiveSession(targetConversationId: string) {
    const { error } = await request({
      url: `users/conversations/${targetConversationId}/unarchive`,
      method: 'PUT'
    });
    if (!error) {
      await loadSessions();
    }
  }

  // 会话重命名（pm-016）：PATCH 持久化后即时更新本地标题，不整表刷新
  async function renameSession(targetConversationId: string, title: string) {
    const trimmed = title.trim();
    if (!trimmed) {
      return false;
    }
    const { error } = await request({
      url: `users/conversations/${targetConversationId}/title`,
      method: 'PATCH',
      data: { title: trimmed }
    });
    if (!error) {
      const session = sessions.value.find(s => s.conversationId === targetConversationId);
      if (session) {
        session.title = trimmed;
      }
    }
    return !error;
  }

  // 会话删除（pm-016）：删除后本地移除；删的是当前会话则清选中态与消息区，回到欢迎区
  async function deleteSession(targetConversationId: string) {
    const { error } = await request({
      url: `users/conversations/${targetConversationId}`,
      method: 'DELETE'
    });
    if (error) {
      return false;
    }
    sessions.value = sessions.value.filter(s => s.conversationId !== targetConversationId);
    if (targetConversationId === conversationId.value) {
      conversationId.value = '';
      list.value = [];
      sessionStorage.removeItem(ACTIVE_SESSION_STORAGE_KEY);
    }
    return true;
  }

  const filteredSessions = computed(() => {
    return sessions.value.filter(s => s.status === (activeTab.value === 'archived' ? 'ARCHIVED' : 'ACTIVE'));
  });

  // ---- WebSocket ----

  const socketUrl = computed(() => {
    const token = store.token?.trim();
    if (!token) {
      return undefined;
    }
    return `/proxy-ws/chat/${encodeURIComponent(token)}`;
  });

  const {
    status: wsStatus,
    data: wsData,
    send: rawWsSend,
    open: rawWsOpen,
    close: rawWsClose
  } = useWebSocket(socketUrl, {
    immediate: false,
    autoConnect: false,
    heartbeat: {
      message: WS_HEARTBEAT_PING,
      responseMessage: WS_HEARTBEAT_PONG,
      interval: 20_000,
      pongTimeout: 10_000
    },
    autoReconnect: {
      retries: () => allowReconnect.value,
      delay: 1500,
      onFailed: () => {
        if (allowReconnect.value && socketUrl.value) {
          window.$message?.warning('WebSocket 重连失败，请检查网络或刷新页面后重试');
        }
      }
    },
    onConnected: () => {
      allowReconnect.value = true;
      authFailureNotified.value = false;
      intentionalDisconnect.value = false;
      // pm-020：从升级横幅中恢复时闭环提示一次，横幅由 connectionEscalated 复位自行消失
      const recoveredFromEscalation = connectionEscalated.value;
      clearConnectionEscalation();
      if (recoveredFromEscalation) {
        window.$message?.success('聊天连接已恢复');
      }
    },
    onDisconnected: (_, event) => {
      if (intentionalDisconnect.value) {
        intentionalDisconnect.value = false;
        allowReconnect.value = Boolean(socketUrl.value);
        clearConnectionEscalation();
        return;
      }
      const closedBeforeHandshake = !handshakeConfirmed.value;
      const isAuthOrProtocolFailure = NON_RETRYABLE_CLOSE_CODES.has(event.code) || closedBeforeHandshake;
      allowReconnect.value = !isAuthOrProtocolFailure;
      if (isAuthOrProtocolFailure) {
        // 鉴权/协议失败不走重连：清掉升级计时，交由既有的鉴权错误提示
        clearConnectionEscalation();
        if (!authFailureNotified.value) {
          authFailureNotified.value = true;
          window.$message?.error('聊天连接鉴权失败，请重新登录后再试');
        }
        return;
      }
      // pm-020 断线即轻提示（整轮只提示一次，重连中的『重连中』状态位已由 connectionStatus 承载）；
      // 持续断线超过阈值升级为持续横幅（input-box 消费 connectionEscalated，不自动消失）
      if (!disconnectNotified.value) {
        disconnectNotified.value = true;
        window.$message?.warning('聊天连接已断开，正在自动重连…');
      }
      startConnectionEscalation();
    }
  });

  function resumeGeneration(generationId: string) {
    rawWsSend(JSON.stringify({ type: 'resume', generationId }));
  }

  function syncRateLimitCountdown() {
    if (!rateLimitUntil.value) {
      rateLimitRemainingSeconds.value = 0;
      return;
    }
    const remainingMs = rateLimitUntil.value - Date.now();
    rateLimitRemainingSeconds.value = Math.max(0, Math.ceil(remainingMs / 1000));
    if (remainingMs <= 0) {
      clearRateLimitCountdown();
    }
  }

  function clearRateLimitTimer() {
    if (rateLimitTimer !== null) {
      window.clearInterval(rateLimitTimer);
      rateLimitTimer = null;
    }
  }

  function clearRateLimitCountdown() {
    clearRateLimitTimer();
    rateLimitUntil.value = null;
    rateLimitRemainingSeconds.value = 0;
  }

  function startRateLimitCountdown(retryAfterSeconds: number) {
    const normalizedSeconds = Math.max(0, Math.ceil(retryAfterSeconds));
    if (normalizedSeconds <= 0) {
      clearRateLimitCountdown();
      return;
    }
    rateLimitUntil.value = Date.now() + normalizedSeconds * 1000;
    syncRateLimitCountdown();
    clearRateLimitTimer();
    rateLimitTimer = setInterval(syncRateLimitCountdown, 1000);
  }

  function resetConnectionState() {
    handshakeConfirmed.value = false;
    sessionId.value = '';
    authFailureNotified.value = false;
    // pm-020：这里不清 connectionEscalated——手动「立即重连」尝试期间横幅保持显示（如实反映尚未恢复），
    // 成功后由 onConnected 清除并提示恢复；登出/主动断开路径由 onDisconnected 的 intentional 分支清除
  }

  function wsOpen() {
    if (!socketUrl.value) {
      return;
    }
    resetConnectionState();
    allowReconnect.value = true;
    intentionalDisconnect.value = wsStatus.value === 'OPEN' || wsStatus.value === 'CONNECTING';
    rawWsOpen();
  }

  function wsClose(code?: number, reason?: string) {
    intentionalDisconnect.value = true;
    allowReconnect.value = false;
    rawWsClose(code, reason);
  }

  function handleAuthReset() {
    clearRateLimitCountdown();
    resetConnectionState();
    conversationId.value = '';
    input.value = { message: '' };
    list.value = [];
    sessions.value = [];
    wsClose(1000, 'auth-reset');
  }

  watch(
    socketUrl,
    url => {
      resetConnectionState();
      if (!url) {
        wsClose();
        clearRateLimitCountdown();
        return;
      }
      wsOpen();
    },
    { immediate: true }
  );

  watch(wsData, val => {
    if (!val) return;
    try {
      const data = JSON.parse(val);
      if (data.type === 'connection' && data.sessionId) {
        handshakeConfirmed.value = true;
        sessionId.value = data.sessionId;
        syncGenerationAfterReconnect().catch(() => {});
      }
    } catch {
      // Ignore JSON parse errors for non-JSON messages
    }
  });

  const scrollToBottom = ref<null | (() => void)>(null);
  const isRateLimited = computed(() => rateLimitRemainingSeconds.value > 0);
  const connectionStatus = computed(() => {
    if (wsStatus.value === 'OPEN') {
      return 'OPEN';
    }
    if (wsStatus.value === 'CONNECTING' && handshakeConfirmed.value) {
      return 'RECONNECTING';
    }
    return wsStatus.value;
  });

  /** 重试本问（pm-001）：用失败回合的原提问重发该回合——保留用户消息， 就地把失败态助手消息换成新的 pending 消息。只支持最后一回合，避免把历史中间的问题追加到会话尾部。 */
  function retryFailedGeneration(message: Api.Chat.Message) {
    const index = list.value.findIndex(item => item === message);
    if (index < 0) return;
    if (index !== list.value.length - 1) {
      window.$message?.warning('只能重试最后一轮问答');
      return;
    }
    if (isRateLimited.value) {
      window.$message?.warning(`当前发送受限，${rateLimitRemainingSeconds.value} 秒后可重新发送`);
      return;
    }
    if (['CLOSED', 'CONNECTING'].includes(connectionStatus.value)) {
      window.$message?.warning('聊天连接未就绪，请稍后重试');
      return;
    }
    if (list.value.some(item => item?.role === 'assistant' && ['pending', 'loading'].includes(item.status || ''))) {
      return;
    }

    // 原提问：失败回答前面最近的一条用户消息（刷新后的历史回合同样满足）
    let question = '';
    for (let i = index - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'user') {
        question = item.content || '';
        break;
      }
    }
    if (!question.trim()) return;

    const targetConversationId = message.conversationId || conversationId.value;
    if (!targetConversationId) return;

    conversationId.value = targetConversationId;
    list.value[index] = {
      content: '',
      role: 'assistant',
      status: 'pending',
      conversationId: targetConversationId,
      toolEvents: []
    };
    rawWsSend(JSON.stringify({ type: 'chat', message: question, conversationId: targetConversationId }));
  }

  return {
    input,
    conversationId,
    list,
    sessions,
    sessionsLoading,
    activeTab,
    filteredSessions,
    connectionStatus,
    connectionEscalated,
    isRateLimited,
    rateLimitRemainingSeconds,
    wsStatus,
    wsData,
    wsSend: rawWsSend,
    wsOpen,
    wsClose,
    sessionId,
    scrollToBottom,
    clearRateLimitCountdown,
    startRateLimitCountdown,
    handleAuthReset,
    fetchGenerationSnapshot,
    fetchActiveGenerationSnapshot,
    resumeGeneration,
    upsertGenerationSnapshot,
    syncGenerationAfterReconnect,
    retryFailedGeneration,
    loadSessions,
    createNewSession,
    switchSession,
    loadMessages,
    archiveSession,
    unarchiveSession,
    renameSession,
    deleteSession
  };
});
