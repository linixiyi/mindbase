<script setup lang="ts">
import { createStreamBuffer } from '../stream/buffer';
import { reduceStreamFrame } from '../stream/reducer';

const chatStore = useChatStore();
const { connectionStatus, connectionEscalated, input, isRateLimited, list, rateLimitRemainingSeconds, wsData } =
  storeToRefs(chatStore);

/** reducer 是纯函数（不修改入参、返回新消息对象）；这里把归约结果原地合并回响应式消息， 保持列表项引用稳定——生成状态监控闭包依赖 list.includes(assistant) 判断。 */
function applyReducedStreamState(assistant: Api.Chat.Message, next: Api.Chat.Message) {
  Object.assign(assistant, next);
}

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

const preparingSession = ref(false);
let generationStatusTimer: number | null = null;
let lastStreamContentLength = 0;
let lastStreamContentChangedAt = 0;

/**
 * chunk 批量缓冲（task-0002）：chunk 不再逐帧直写 assistant.content（VueMarkdownIt 会整段重渲染，长答案 O(n²)），同窗口（50ms）合并为一次 reducer 更新。 推送目标在
 * push 时锁定，flush 时校验仍在列表且未到终态再应用——迟到 chunk 不得把已 finished/error 的消息拉回 loading。
 */
let streamChunkTarget: Api.Chat.Message | null = null;
const streamBuffer = createStreamBuffer({
  flushIntervalMs: 50,
  onFlush: (_generationId, text) => {
    const assistant = streamChunkTarget;
    if (!assistant || !list.value.includes(assistant)) return;
    if (!['pending', 'loading'].includes(assistant.status || '')) return;
    applyReducedStreamState(assistant, reduceStreamFrame(assistant, { type: 'chunk', chunk: text }));
    lastStreamContentLength = assistant.content.length;
    lastStreamContentChangedAt = Date.now();
  }
});

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendDisabled = computed(() => {
  if (preparingSession.value) return true;
  // 生成中不发送（task-0004）：中断走独立停止按钮，Enter 只提示不发送
  if (isSending.value) return true;
  if (isRateLimited.value) {
    return true;
  }
  return !input.value.message || ['CLOSED', 'CONNECTING'].includes(connectionStatus.value);
});

const connectionText = computed(() => {
  if (connectionStatus.value === 'OPEN') {
    return '已连接';
  }
  if (connectionStatus.value === 'RECONNECTING') {
    return '重连中';
  }
  if (connectionStatus.value === 'CONNECTING') {
    return '连接中';
  }
  return '未连接';
});

const cooldownText = computed(() => {
  if (!isRateLimited.value) {
    return '';
  }
  return `${rateLimitRemainingSeconds} 秒后可重新发送`;
});

function findAssistantMessage(generationId?: string, payloadConversationId?: string, allowUnbound = false) {
  if (payloadConversationId && payloadConversationId !== chatStore.conversationId) return null;
  if (generationId) {
    for (let i = list.value.length - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'assistant' && item.generationId === generationId) {
        return item;
      }
    }
  }

  const latest = list.value[list.value.length - 1];
  if (
    latest?.role === 'assistant' &&
    ['pending', 'loading'].includes(latest.status || '') &&
    (!generationId || (allowUnbound && !latest.generationId))
  ) {
    return latest;
  }

  return null;
}

function handleStartPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  // start（尤其重连 resumed）前先输出在途 chunk：resumed 内容按长度取大，先落字符避免重复拼接
  streamBuffer.flush();
  applyReducedStreamState(
    assistant,
    reduceStreamFrame(assistant, {
      type: 'start',
      generationId: payload.generationId,
      conversationId: payload.conversationId,
      timestamp: payload.timestamp,
      resumed: payload.resumed,
      content: payload.content
    })
  );
}

async function handleCompletionPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  // 终态前必须 flush（task-0002）：先落在途 chunk，再收敛状态，保证字符不丢、顺序不变
  streamBuffer.flush();
  applyReducedStreamState(
    assistant,
    reduceStreamFrame(assistant, {
      type: 'completion',
      status: payload.status,
      referenceMappings: payload.referenceMappings
    })
  );
  stopGenerationStatusMonitor();
  // Reconcile any chunks missed while reconnecting with the final server snapshot.
  if (assistant.generationId && payload.status === 'finished') {
    const snapshot = await chatStore.fetchGenerationSnapshot(assistant.generationId);
    if (snapshot && snapshot.status !== 'STREAMING' && list.value.includes(assistant)) {
      chatStore.upsertGenerationSnapshot(snapshot);
    }
  }
}

function handleStopPayload(assistant: Api.Chat.Message) {
  // 终态前必须 flush（task-0002）；task-0004 停止语义：后端确认的 stop 帧把生成中消息收敛为 stopped
  streamBuffer.flush();
  applyReducedStreamState(assistant, reduceStreamFrame(assistant, { type: 'stop' }));
  stopGenerationStatusMonitor();
}

function handleErrorPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  // 终态前必须 flush（task-0002）：先落在途 chunk，再写错误话术覆盖正文
  streamBuffer.flush();
  const rateLimited = Number(payload.code) === 429;
  const next = reduceStreamFrame(assistant, {
    type: 'error',
    code: payload.code,
    retryAfterSeconds: payload.retryAfterSeconds,
    error: payload.error,
    message: payload.message,
    errorCategory: payload.errorCategory
  });
  applyReducedStreamState(assistant, next);

  if (rateLimited) {
    chatStore.startRateLimitCountdown(Number(payload.retryAfterSeconds || 0));
  }
  stopGenerationStatusMonitor();

  // error 帧归约后 content 即用户话术（429/error/message/字典兜底），直接用于提示
  if (rateLimited) {
    window.$message?.warning(next.content);
  } else {
    window.$message?.error(next.content);
  }
}

function handleChunkPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  // 目标消息在 push 时锁定（chunk 帧可能不带 generationId，watch 已按既有口径解析好目标）
  streamChunkTarget = assistant;
  streamBuffer.push(assistant.generationId || '', typeof payload.chunk === 'string' ? payload.chunk : '');
}

function stopGenerationStatusMonitor() {
  if (generationStatusTimer !== null) {
    window.clearInterval(generationStatusTimer);
    generationStatusTimer = null;
  }
}

function isCurrentPending(assistant: Api.Chat.Message) {
  return list.value.includes(assistant) && ['pending', 'loading'].includes(assistant.status || '');
}

function startGenerationStatusMonitor() {
  stopGenerationStatusMonitor();
  const assistant = latestMessage.value;
  const targetConversationId = chatStore.conversationId;
  const question = list.value[list.value.length - 2]?.content;
  lastStreamContentLength = assistant.content?.length || 0;
  lastStreamContentChangedAt = Date.now();
  let checking = false;
  generationStatusTimer = window.setInterval(async () => {
    if (!isCurrentPending(assistant)) {
      stopGenerationStatusMonitor();
      return;
    }
    if (assistant.content.length !== lastStreamContentLength) {
      lastStreamContentLength = assistant.content.length;
      lastStreamContentChangedAt = Date.now();
      return;
    }
    if (checking || Date.now() - lastStreamContentChangedAt < 8000) return;

    checking = true;
    try {
      const snapshot = assistant.generationId
        ? await chatStore.fetchGenerationSnapshot(assistant.generationId)
        : await chatStore.fetchActiveGenerationSnapshot();
      if (!isCurrentPending(assistant) || chatStore.conversationId !== targetConversationId) return;
      if (snapshot && snapshot.conversationId === targetConversationId && snapshot.question === question) {
        if (!assistant.generationId) {
          assistant.generationId = snapshot.generationId;
          if (snapshot.status === 'STREAMING') chatStore.resumeGeneration(snapshot.generationId);
        }
        // A terminal snapshot is authoritative; replacing partial streaming text could duplicate queued chunks.
        if (snapshot.status !== 'STREAMING') {
          chatStore.upsertGenerationSnapshot(snapshot);
          if (assistant.status === 'error') markExecutingToolsAsFailed(assistant);
          else markExecutingToolsAsSuccess(assistant);
          stopGenerationStatusMonitor();
          return;
        }
      }
    } catch {
      // A temporary snapshot failure must not stop subsequent checks.
    } finally {
      checking = false;
    }
    if (isCurrentPending(assistant) && Date.now() - lastStreamContentChangedAt > 130_000) {
      assistant.status = 'error';
      assistant.content ||= '暂时无法同步回答，请刷新后查看结果。';
      markExecutingToolsAsFailed(assistant);
      window.$message?.warning('回答同步超时，请刷新后查看结果');
      stopGenerationStatusMonitor();
    }
  }, 2000);
}

function markExecutingToolsAsSuccess(assistant: Api.Chat.Message) {
  updateExecutingToolStatus(assistant, 'success');
}

function markExecutingToolsAsFailed(assistant: Api.Chat.Message) {
  updateExecutingToolStatus(assistant, 'failed');
}

function updateExecutingToolStatus(assistant: Api.Chat.Message, status: Api.Chat.AgentToolEvent['status']) {
  if (!assistant.toolEvents?.length) {
    return;
  }

  let changed = false;
  const timestamp = Date.now();
  const toolEvents = assistant.toolEvents.map(event => {
    if (event.status !== 'executing') {
      return event;
    }
    changed = true;
    return {
      ...event,
      status,
      timestamp
    };
  });

  if (changed) {
    assistant.toolEvents = toolEvents;
  }
}

function handleToolCallPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  const id = typeof payload.toolCallId === 'string' ? payload.toolCallId : '';
  const tool = typeof payload.tool === 'string' ? payload.tool : '';
  const status = typeof payload.status === 'string' ? payload.status : 'executing';
  if (!tool || !['executing', 'success', 'failed'].includes(status)) {
    return;
  }

  assistant.status = 'loading';
  assistant.toolEvents ||= [];
  const event = {
    id,
    tool,
    status: status as Api.Chat.AgentToolEvent['status'],
    timestamp: Number(payload.timestamp || Date.now())
  };

  const matchEvent = (item: Api.Chat.AgentToolEvent) => {
    if (id && item.id) {
      return item.id === id;
    }
    if (!id && !item.id) {
      return item.tool === tool;
    }
    return false;
  };

  if (assistant.toolEvents.some(matchEvent)) {
    assistant.toolEvents = assistant.toolEvents.map(item => (matchEvent(item) ? event : item));
  } else {
    assistant.toolEvents = [...assistant.toolEvents, event];
  }
}

watch(wsData, val => {
  if (!val) return;

  let payload: Record<string, any>;

  try {
    payload = JSON.parse(val);
  } catch {
    return;
  }

  // 知识库文档状态推送和聊天共用一条连接，聊天这边不处理
  if (payload.type === 'document_status') return;

  const assistant = findAssistantMessage(payload.generationId, payload.conversationId, payload.type === 'start');

  if (!assistant) return;

  if (payload.type === 'start') {
    handleStartPayload(assistant, payload);
    return;
  }

  if (payload.type === 'completion') {
    handleCompletionPayload(assistant, payload);
    return;
  }

  if (payload.type === 'tool_call') {
    handleToolCallPayload(assistant, payload);
    return;
  }

  if (payload.type === 'stop') {
    handleStopPayload(assistant);
    return;
  }

  if (payload.error || Number(payload.code) >= 400) {
    handleErrorPayload(assistant, payload);
    return;
  }

  if (payload.chunk) {
    handleChunkPayload(assistant, payload);
  }
});

const handleSend = async () => {
  // 生成中 Enter 不再中断（task-0004）：提示改用停止按钮，不发送
  if (isSending.value) {
    window.$message?.warning('生成中，点击停止按钮可中断');
    return;
  }
  if (sendDisabled.value) return;
  if (isRateLimited.value) {
    window.$message?.warning(`当前发送受限，${cooldownText.value}`);
    return;
  }

  if (!chatStore.conversationId) {
    preparingSession.value = true;
    try {
      if (!(await chatStore.createNewSession())) return;
    } finally {
      preparingSession.value = false;
    }
  }

  list.value.push({
    content: input.value.message,
    role: 'user',
    conversationId: chatStore.conversationId
  });
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending',
    conversationId: chatStore.conversationId,
    toolEvents: []
  });
  chatStore.wsSend(
    JSON.stringify({ type: 'chat', message: input.value.message, conversationId: chatStore.conversationId })
  );
  input.value.message = '';
};

/**
 * 停止生成（task-0004）：独立停止按钮触发。后端确认前不得本地假装 stopped——cmd token 获取失败时 保持 loading 并可见报错（不再静默 return）；命令发出后由后端回推的 stop 帧经
 * handleStopPayload → reducer 收敛为 stopped。
 */
const handleStop = async () => {
  const { error, data: tokenData } = await request<Api.Chat.Token>({
    url: 'chat/websocket-token'
  });
  if (error) {
    window.$message?.error('停止失败，请重试');
    return;
  }

  chatStore.wsSend(
    JSON.stringify({
      type: 'stop',
      generationId: latestMessage.value.generationId,
      _internal_cmd_token: tokenData.cmdToken
    })
  );
};

const inputRef = ref();
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus();
  });
};

const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    // IME 组合输入防护（pm-005）：中文输入法按 Enter 确认候选词时，Chrome 系会下发
    // key='Enter' 且 isComposing=true / keyCode 229 的 keydown——此时放行默认行为，不触发发送
    if (e.isComposing || e.keyCode === 229) return;
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};

function reloadPage() {
  window.location.reload();
}

watch(
  () => (isSending.value ? latestMessage.value : null),
  sending => {
    if (sending) startGenerationStatusMonitor();
    else stopGenerationStatusMonitor();
  },
  { immediate: true }
);

onUnmounted(() => {
  // dispose 先输出剩余缓冲（尾部不丢）再清定时器
  streamBuffer.dispose();
  stopGenerationStatusMonitor();
});
</script>

<template>
  <div class="relative shrink-0 px-4 pb-4 pt-2">
    <!--
      pm-020 断线升级横幅：持续断线超过 10s 出现，不自动消失——含手动刷新引导与「立即重连」动作。
      材质沿用既有警示提示配方（warning 10% 底 + 语义深阶文字，同 reference-preview 的 .preview-page-tip），
      复用 store 与 connectionStatus 同源的状态机（connectionEscalated），不新起机制
    -->
    <div v-if="connectionEscalated" class="chat-connection-banner" role="alert">
      <icon-lucide:wifi-off class="shrink-0 text-icon" />
      <span class="chat-connection-banner__text">
        聊天连接已断开超过 10 秒，自动重连仍未成功。请检查网络后重试，或刷新页面。
      </span>
      <NButton size="tiny" type="primary" secondary @click="chatStore.wsOpen()">立即重连</NButton>
      <NButton size="tiny" secondary @click="reloadPage">刷新页面</NButton>
    </div>
    <div class="chat-input-shell mx-auto max-w-[800px] w-full flex items-end gap-2">
      <textarea
        ref="inputRef"
        v-model.trim="input.message"
        aria-label="输入消息"
        placeholder="给 MindBase 发送消息，Enter 发送，Shift+Enter 换行"
        class="text-body max-h-32 min-h-6 w-full flex-1 resize-none border-none bg-transparent py-1 text-16px caret-[rgb(var(--primary-color))] outline-none"
        @keydown="handShortcut"
      />
      <!-- task-0004：发送与停止拆为两个按钮，停止按钮仅生成中显示并带可读名称 -->
      <NButton
        v-if="isSending"
        class="shrink-0 self-end"
        size="small"
        circle
        type="warning"
        aria-label="停止生成"
        @click="handleStop"
      >
        <template #icon>
          <icon-lucide:square class="text-icon-small" />
        </template>
      </NButton>
      <!-- task-0005：发送按钮是纯图标按钮，补可读名称（aria-label 经 attrs 透传落到原生 button 上） -->
      <NButton
        v-else
        :disabled="sendDisabled"
        class="shrink-0 self-end"
        size="small"
        circle
        type="primary"
        aria-label="发送"
        @click="handleSend"
      >
        <template #icon>
          <icon-lucide:arrow-up class="text-icon-small" />
        </template>
      </NButton>
    </div>
    <div class="mx-auto mt-1.5 max-w-[800px] w-full flex items-center justify-between px-1">
      <div class="flex items-center gap-2">
        <div class="flex items-center gap-1">
          <span
            class="inline-block h-1.5 w-1.5 rounded-full"
            :class="{
              'bg-success': connectionStatus === 'OPEN',
              'bg-warning animate-pulse': connectionStatus === 'CONNECTING' || connectionStatus === 'RECONNECTING',
              'bg-error': connectionStatus === 'CLOSED'
            }"
          />
          <span class="text-muted text-12px">{{ connectionText }}</span>
        </div>
        <span v-if="isRateLimited" class="chat-cooldown text-12px">{{ cooldownText }}</span>
      </div>
      <span class="text-muted text-12px">Shift+Enter 换行</span>
    </div>
  </div>
</template>

<style scoped>
textarea::placeholder {
  color: var(--app-muted);
}

/* 聊天输入壳：18px 圆角、实底 + 发丝线，轻量化静置——白画布上只靠发丝线与一层低语级投影浮起
   （第三轮 airy：静置投影比 elevation-1 更轻；DESIGN.md › Inputs / Fields 聊天输入壳放大版） */
.chat-input-shell {
  padding: 12px 14px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-lg, 18px);
  background: var(--app-elevated, var(--app-surface));
  box-shadow:
    0 1px 2px rgba(0, 0, 0, 0.04),
    0 2px 8px rgba(0, 0, 0, 0.04);
  transition:
    border-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    box-shadow var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);
}

.chat-input-shell:focus-within {
  border-color: rgb(var(--primary-color) / 0.4);
  box-shadow:
    0 1px 2px rgba(0, 0, 0, 0.04),
    0 4px 16px rgba(0, 0, 0, 0.08),
    0 0 0 3px rgb(var(--primary-color) / 0.15);
}

.chat-input-shell textarea {
  font-size: 16px;
  line-height: 1.75;
}

/* 限流冷却：语义橙深阶文字（浅 -700 / 暗 -400） */
.chat-cooldown {
  color: rgb(var(--warning-700-color));
}

.dark .chat-cooldown {
  color: rgb(var(--warning-400-color));
}

/* pm-020 断线升级横幅：warning 10% 底 + 语义深阶文字（浅 -700 / 暗 -400），与
   reference-preview 的 .preview-page-tip 同配方（DESIGN.md › 语义深阶律）；圆角取提示类 10px */
.chat-connection-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 auto 8px;
  max-width: 800px;
  width: 100%;
  padding: 8px 14px;
  border-radius: 10px;
  background: rgb(var(--warning-color) / 0.1);
  color: rgb(var(--warning-700-color));
  font-size: 13px;
  line-height: 1.5;
}

.chat-connection-banner__text {
  flex: 1;
  min-width: 0;
}

.dark .chat-connection-banner {
  color: rgb(var(--warning-400-color));
}
</style>
