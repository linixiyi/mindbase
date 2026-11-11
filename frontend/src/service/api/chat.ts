import { request } from '../request';

// ---- 聊天相关 HTTP 请求收口（task-0008）----
// 只做参数与返回类型包装：URL / 方法 / 错误码约定与既有调用完全一致，不改后端行为。
// 消费方：store/modules/chat（历史加载、切换、归档）与 chat 视图（停止令牌等）。

/** 历史消息：GET users/conversation?conversationId=xxx（task-0003 起 AbortSignal 随请求透传，切会话可取消在途请求） */
export function fetchConversationMessages(conversationId: string, signal?: AbortSignal) {
  return request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: { conversationId },
    signal
  });
}

/** 切换会话：PUT users/conversations/{id}/switch */
export function switchConversation(conversationId: string) {
  return request({
    url: `users/conversations/${conversationId}/switch`,
    method: 'PUT'
  });
}

/** 归档会话：PUT users/conversations/{id}/archive */
export function archiveConversation(conversationId: string) {
  return request({
    url: `users/conversations/${conversationId}/archive`,
    method: 'PUT'
  });
}

/** 消息反馈：POST chat/feedback（chat-message.vue 的点赞/点踩上报，字段与既有调用一致） */
export function sendFeedback(params: {
  rating: 'good' | 'bad';
  reason: string;
  conversationId?: string;
  generationId?: string;
}) {
  return request({
    url: 'chat/feedback',
    method: 'POST',
    data: params
  });
}

/** 停止生成用的命令令牌：GET chat/websocket-token（input-box.vue › handleStop 消费 cmdToken） */
export function fetchWebsocketToken() {
  return request<Api.Chat.Token>({
    url: 'chat/websocket-token'
  });
}

// ---- WS 帧统一解析（task-0008）----
// 帧类型见 typings/api.d.ts › Api.Chat.ChatStreamFrame（判别联合，每帧显式 type）。
// 此前 input-box.vue 与 knowledge-base/index.vue 各自手写 try { JSON.parse } —— 解析失败、
// 缺 type、未知 type 的兜底行为不一致。统一从这里走：任何输入都不抛异常。

/** 后端 WS 帧类型全集（Api.Chat.ChatStreamFrame 联合成员的 type 字面量） */
const CHAT_FRAME_TYPES = new Set([
  'start',
  'chunk',
  'completion',
  'stop',
  'error',
  'tool_call',
  'connection',
  'document_status'
]);

function isChatStreamFrame(value: unknown): value is Api.Chat.ChatStreamFrame {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const type = (value as { type?: unknown }).type;
  return typeof type === 'string' && CHAT_FRAME_TYPES.has(type);
}

/**
 * 解析一条 WS 报文为判别联合帧。永不抛异常：
 *
 * - JSON 解析失败 / 解析结果不是对象 / 缺 type / type 不在后端帧全集 → 返回 { type: 'unknown', raw }；
 * - 合法帧按 type 原样返回（不清洗字段，调用方按自己的消费口径取用）。
 */
export function parseStreamFrame(raw: string): Api.Chat.ChatStreamFrame | Api.Chat.ChatUnknownFrame {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { type: 'unknown', raw };
  }
  if (!isChatStreamFrame(parsed)) {
    return { type: 'unknown', raw };
  }
  return parsed;
}
