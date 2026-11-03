import { useWebSocket } from '@vueuse/core';
import { getChatFailureCopy } from '@/constants/chat-failure';
import { request } from '@/service/request';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const NON_RETRYABLE_CLOSE_CODES = new Set([1002, 1003, 1007, 1008]);
  const WS_HEARTBEAT_PING = '__chat_ping__';
  const WS_HEARTBEAT_PONG = '__chat_pong__';

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
      // Auto-select first active session if none is active
      if (!conversationId.value) {
        const firstActive = data.find(s => s.status === 'ACTIVE');
        if (firstActive) {
          await switchSession(firstActive.conversationId);
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
    },
    onDisconnected: (_, event) => {
      if (intentionalDisconnect.value) {
        intentionalDisconnect.value = false;
        allowReconnect.value = Boolean(socketUrl.value);
        return;
      }
      const closedBeforeHandshake = !handshakeConfirmed.value;
      const isAuthOrProtocolFailure = NON_RETRYABLE_CLOSE_CODES.has(event.code) || closedBeforeHandshake;
      allowReconnect.value = !isAuthOrProtocolFailure;
      if (isAuthOrProtocolFailure && !authFailureNotified.value) {
        authFailureNotified.value = true;
        window.$message?.error('聊天连接鉴权失败，请重新登录后再试');
      }
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
    unarchiveSession
  };
});
