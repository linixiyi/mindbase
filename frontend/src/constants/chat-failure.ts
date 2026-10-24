/**
 * 聊天失败用户话术字典（pm-001）： 后端 error 帧 / 生成快照携带 errorCategory（与 ChatHandler.ChatFailureCategory 同名），
 * 前端按类别分层文案。「繁忙/不可用」类话术只在这里维护一份，与后端 failureUserMessage 对应。
 */
export type ChatFailureCategory = 'PROVIDER_UNAVAILABLE' | 'TIMEOUT' | 'BUSY' | 'RATE_LIMIT' | 'OTHER';

interface ChatFailureCopy {
  /** 错误卡片标题（按类别分层） */
  title: string;
  /** 次要引导说明 */
  hint: string;
  /** 无具体消息时的兜底话术 */
  message: string;
}

const DEFAULT_CATEGORY: ChatFailureCategory = 'OTHER';

const CHAT_FAILURE_COPY: Record<ChatFailureCategory, ChatFailureCopy> = {
  PROVIDER_UNAVAILABLE: {
    title: '模型服务不可用',
    hint: '若持续失败，请让管理员在「模型供应商」页检查配置',
    message: '模型服务暂时不可用，请稍后重试'
  },
  TIMEOUT: {
    title: '回答生成超时',
    hint: '模型响应耗时过长已中止，可点击重试再试一次',
    message: '回答生成超时，请稍后重试'
  },
  BUSY: {
    title: '系统繁忙',
    hint: '当前请求较多，稍作等待后再试',
    message: '系统繁忙，请稍后重试'
  },
  RATE_LIMIT: {
    title: '发送过于频繁',
    hint: '等待冷却结束后可重新发送',
    message: '请求过于频繁，请稍后重试'
  },
  OTHER: {
    title: 'AI服务暂时不可用',
    hint: '若持续失败，请联系管理员检查服务状态',
    message: 'AI服务暂时不可用，请稍后重试'
  }
};

export function normalizeChatFailureCategory(category?: string | null): ChatFailureCategory {
  return category && category in CHAT_FAILURE_COPY ? (category as ChatFailureCategory) : DEFAULT_CATEGORY;
}

export function getChatFailureCopy(category?: string | null): ChatFailureCopy {
  return CHAT_FAILURE_COPY[normalizeChatFailureCategory(category)];
}

/** WS error 帧缺少具体话术时的统一兜底（原「服务器繁忙，请稍后再试」收口到这里） */
export function getChatFailureFallbackMessage(): string {
  return CHAT_FAILURE_COPY[DEFAULT_CATEGORY].message;
}
