/**
 * 聊天流式帧归约（task-0001）：把原本内联在 input-box.vue 五个 handle* 里的消息状态迁移 收敛为纯函数 reduceStreamFrame(state, frame)——不修改入参，返回新的消息状态；
 * input-box.vue 只负责把结果合并回响应式列表并保留副作用（滚动、消息提示、timer）。
 *
 * 帧字段以现有消费口径命名，不改后端 WS 协议：
 *
 * - start/chunk/completion/stop/error 按 input-box.vue watch(wsData) 消费的字段；
 * - snapshot 对应重连快照（store.fetchGenerationSnapshot / upsertGenerationSnapshot 消费的
 *   Api.Chat.GenerationSnapshot），本卡只提供归约能力，store 侧落账逻辑不动。
 */
import { getChatFailureCopy, getChatFailureFallbackMessage } from '../../../constants/chat-failure';

/** 流式归约的状态：即消息列表里的 assistant 消息 */
export type ChatStreamState = Api.Chat.Message;

export interface ChatStartFrame {
  type: 'start';
  generationId?: string;
  conversationId?: string;
  /** 后端时间戳：字符串或毫秒数，归约时统一转 ISO 存入消息 */
  timestamp?: string | number;
  /** 重复提交同一问题时后端复用进行中的回答，start 帧携带已生成内容 */
  resumed?: boolean;
  content?: string;
}

export interface ChatChunkFrame {
  type: 'chunk';
  chunk?: string;
}

export interface ChatCompletionFrame {
  type: 'completion';
  status?: 'finished' | 'failed';
  referenceMappings?: Record<string, Api.Chat.ReferenceEvidence>;
}

export interface ChatStopFrame {
  type: 'stop';
}

export interface ChatErrorFrame {
  type: 'error';
  code?: number;
  retryAfterSeconds?: number;
  error?: string;
  message?: string;
  errorCategory?: string;
}

/** 重连快照帧：字段与 GET chat/generation/{generationId} 返回的生成快照一致 */
export interface ChatSnapshotFrame extends Api.Chat.GenerationSnapshot {
  type: 'snapshot';
}

export type ChatStreamFrame =
  | ChatStartFrame
  | ChatChunkFrame
  | ChatCompletionFrame
  | ChatStopFrame
  | ChatErrorFrame
  | ChatSnapshotFrame;

/** WS error 帧的用户话术（原 input-box.vue buildWsErrorMessage 同口径）： 429 带冷却秒数 → error → message → chat-failure 字典兜底 */
export function buildChatStreamErrorMessage(frame: ChatErrorFrame): string {
  if (frame.code === 429) {
    const retryAfterSeconds = Number(frame.retryAfterSeconds || 0);
    const baseMessage = frame.message || '聊天请求过于频繁';

    if (retryAfterSeconds > 0) {
      return `${baseMessage}，请在 ${retryAfterSeconds} 秒后重试`;
    }

    return `${baseMessage}，请稍后再试`;
  }

  if (typeof frame.error === 'string' && frame.error.trim()) {
    return frame.error.trim();
  }

  if (typeof frame.message === 'string' && frame.message.trim()) {
    return frame.message.trim();
  }

  return getChatFailureFallbackMessage();
}

/** 把仍在 executing 的工具事件收敛为终态（原 updateExecutingToolStatus 同口径） */
function settleExecutingTools(
  toolEvents: Api.Chat.AgentToolEvent[] | undefined,
  status: Api.Chat.AgentToolEvent['status']
) {
  if (!toolEvents?.length) {
    return toolEvents;
  }

  let changed = false;
  const timestamp = Date.now();
  const nextEvents = toolEvents.map(event => {
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

  return changed ? nextEvents : toolEvents;
}

function reduceStart(state: ChatStreamState, frame: ChatStartFrame): ChatStreamState {
  const next: ChatStreamState = {
    ...state,
    generationId: frame.generationId || state.generationId,
    conversationId: frame.conversationId || state.conversationId
  };
  if (!next.timestamp && frame.timestamp) {
    next.timestamp = new Date(frame.timestamp).toISOString();
  }
  // 重复提交同一问题时后端复用进行中的回答，start 里带上已经生成的内容
  if (frame.resumed && typeof frame.content === 'string' && frame.content.length > next.content.length) {
    next.content = frame.content;
    next.status = 'loading';
  }
  return next;
}

function reduceChunk(state: ChatStreamState, frame: ChatChunkFrame): ChatStreamState {
  const text = typeof frame.chunk === 'string' ? frame.chunk : '';
  return {
    ...state,
    status: 'loading',
    content: state.content + text
  };
}

function reduceCompletion(state: ChatStreamState, frame: ChatCompletionFrame): ChatStreamState {
  let status = state.status;
  // stopped 是用户主动确认的终态（task-0004）：迟到的 finished completion 不把已停止的消息改回 finished
  if (frame.status === 'finished' && status !== 'error' && status !== 'stopped') {
    status = 'finished';
  } else if (frame.status === 'failed') {
    status = 'error';
  }

  const next: ChatStreamState = {
    ...state,
    status,
    toolEvents: settleExecutingTools(state.toolEvents, 'success')
  };
  if (frame.referenceMappings) {
    next.referenceMappings = frame.referenceMappings;
  }
  return next;
}

function reduceStop(state: ChatStreamState): ChatStreamState {
  return {
    ...state,
    // task-0004 停止语义：生成中（pending/loading）收到 stop 收敛为 stopped，与正常完成可区分； 已是终态（error/finished/stopped）不被 stop 帧覆盖
    status: ['pending', 'loading'].includes(state.status || '') ? 'stopped' : state.status,
    toolEvents: settleExecutingTools(state.toolEvents, 'success')
  };
}

function reduceError(state: ChatStreamState, frame: ChatErrorFrame): ChatStreamState {
  const rateLimited = Number(frame.code) === 429;
  const next: ChatStreamState = {
    ...state,
    status: 'error',
    content: buildChatStreamErrorMessage(frame)
  };
  // 失败类别码（pm-001）：429 走限流类别，其余透传后端 errorCategory；错误卡片按类别分层文案
  if (rateLimited) {
    next.errorCategory = 'RATE_LIMIT';
  } else if (typeof frame.errorCategory === 'string' && frame.errorCategory) {
    next.errorCategory = frame.errorCategory;
  } else {
    next.errorCategory = undefined;
  }
  return {
    ...next,
    toolEvents: settleExecutingTools(next.toolEvents, 'failed')
  };
}

function mapSnapshotStatus(status?: Api.Chat.GenerationStatus): ChatStreamState['status'] {
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

/** 快照帧归约：与 store.upsertGenerationSnapshot 对已有 assistant 消息的落账口径一致（pm-001/pm-015） */
function reduceSnapshot(state: ChatStreamState, frame: ChatSnapshotFrame): ChatStreamState {
  // FAILED 快照正文兜底取 chat-failure 字典话术：原始异常/技术串不再兜底为消息正文
  const failureMessage = frame.status === 'FAILED' ? getChatFailureCopy(frame.errorCategory).message : '';
  const next: ChatStreamState = {
    ...state,
    status: mapSnapshotStatus(frame.status),
    content: frame.content || state.content || failureMessage,
    generationId: frame.generationId,
    conversationId: frame.conversationId
  };
  next.timestamp ||= frame.updatedAt;
  if (frame.errorCategory) {
    next.errorCategory = frame.errorCategory;
  }
  if (frame.referenceMappings && Object.keys(frame.referenceMappings).length > 0) {
    next.referenceMappings = frame.referenceMappings;
  }
  return next;
}

/** 流式帧归约纯函数：state + frame → 新的 state。 不修改入参（含嵌套的 toolEvents / referenceMappings），未知帧返回原状态浅拷贝。 */
export function reduceStreamFrame(state: ChatStreamState, frame: ChatStreamFrame): ChatStreamState {
  switch (frame.type) {
    case 'start':
      return reduceStart(state, frame);
    case 'chunk':
      return reduceChunk(state, frame);
    case 'completion':
      return reduceCompletion(state, frame);
    case 'stop':
      return reduceStop(state);
    case 'error':
      return reduceError(state, frame);
    case 'snapshot':
      return reduceSnapshot(state, frame);
    default:
      return { ...state };
  }
}
