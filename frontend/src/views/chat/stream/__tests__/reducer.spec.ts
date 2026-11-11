import { describe, expect, it } from 'vitest';
import { type ChatStreamFrame, type ChatStreamState, reduceStreamFrame } from '../reducer';

function createAssistantMessage(overrides: Partial<ChatStreamState> = {}): ChatStreamState {
  return {
    role: 'assistant',
    content: '',
    status: 'pending',
    conversationId: 'conv-1',
    toolEvents: [],
    ...overrides
  };
}

function deepFreeze<T>(value: T): T {
  if (value && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.keys(value as Record<string, unknown>).forEach(key => {
      deepFreeze((value as Record<string, unknown>)[key]);
    });
    Object.freeze(value);
  }
  return value;
}

/** 冻结入参后归约：reducer 一旦修改入参，strict mode 下抛 TypeError； 同时对比归约前的深拷贝快照兜底断言入参未被修改。 */
function reduceFrozen(state: ChatStreamState, frame: ChatStreamFrame) {
  const stateSnapshot = structuredClone(state);
  const frameSnapshot = structuredClone(frame);
  const next = reduceStreamFrame(deepFreeze(state), deepFreeze(frame));
  expect(state).toEqual(stateSnapshot);
  expect(frame).toEqual(frameSnapshot);
  return next;
}

describe('reduceStreamFrame：流式帧归约（task-0001）', () => {
  it('① start 建立消息并带 generationId（绑定发送时创建的占位消息）', () => {
    // 占位消息由 input-box 的 handleSend 先行入列，start 帧负责绑定身份
    const state = createAssistantMessage();
    const next = reduceFrozen(state, {
      type: 'start',
      generationId: 'gen-1',
      conversationId: 'conv-1',
      timestamp: 1_700_000_000_000
    });

    expect(next.generationId).toBe('gen-1');
    expect(next.conversationId).toBe('conv-1');
    expect(next.timestamp).toBe('2023-11-14T22:13:20.000Z');
    expect(next.status).toBe('pending');
    expect(next.content).toBe('');
  });

  it('② chunk 累加：逐帧顺序拼接且状态转 loading', () => {
    const state = createAssistantMessage({ generationId: 'gen-1' });
    const afterFirst = reduceFrozen(state, { type: 'chunk', chunk: '你' });
    const afterSecond = reduceFrozen(afterFirst, { type: 'chunk', chunk: '好' });

    expect(afterFirst.content).toBe('你');
    expect(afterSecond.content).toBe('你好');
    expect(afterSecond.status).toBe('loading');
  });

  it('③ completion finished + referenceMappings（执行中的工具标记 success）', () => {
    const state = createAssistantMessage({
      generationId: 'gen-1',
      status: 'loading',
      content: '答案正文',
      toolEvents: [{ id: 't1', tool: 'search', status: 'executing', timestamp: 1 }]
    });
    const mappings = { '1': { fileMd5: 'md5-1', fileName: 'a.pdf' } };

    const next = reduceFrozen(state, { type: 'completion', status: 'finished', referenceMappings: mappings });

    expect(next.status).toBe('finished');
    expect(next.referenceMappings).toEqual(mappings);
    expect(next.content).toBe('答案正文');
    expect(next.toolEvents?.[0]?.status).toBe('success');
  });

  it('④ stop 不改内容：生成中停止收敛为 stopped（task-0004 停止语义），终态不被覆盖', () => {
    const state = createAssistantMessage({ generationId: 'gen-1', status: 'loading', content: '部分内容' });
    const next = reduceFrozen(state, { type: 'stop' });

    expect(next.content).toBe('部分内容');
    expect(next.status).toBe('stopped');

    // 已是 error 的消息不被 stop 改写
    const errorState = createAssistantMessage({ status: 'error', content: '失败' });
    const nextOnError = reduceFrozen(errorState, { type: 'stop' });
    expect(nextOnError.status).toBe('error');
    expect(nextOnError.content).toBe('失败');

    // 已正常完成的消息不被迟到的 stop 帧回退为 stopped
    const finishedState = createAssistantMessage({ status: 'finished', content: '完整回答' });
    const nextOnFinished = reduceFrozen(finishedState, { type: 'stop' });
    expect(nextOnFinished.status).toBe('finished');
    expect(nextOnFinished.content).toBe('完整回答');
  });

  it('⑤ error 带 errorCategory：429 归为 RATE_LIMIT，其余透传后端类别', () => {
    const state = createAssistantMessage({ generationId: 'gen-1', status: 'loading', content: '生成中' });

    const rateLimited = reduceFrozen(state, { type: 'error', code: 429, retryAfterSeconds: 30 });
    expect(rateLimited.status).toBe('error');
    expect(rateLimited.errorCategory).toBe('RATE_LIMIT');
    expect(rateLimited.content).toBe('聊天请求过于频繁，请在 30 秒后重试');

    const passthrough = reduceFrozen(state, { type: 'error', code: 500, errorCategory: 'PROVIDER_UNAVAILABLE' });
    expect(passthrough.status).toBe('error');
    expect(passthrough.errorCategory).toBe('PROVIDER_UNAVAILABLE');
    // 无 error/message 时兜底 chat-failure 字典话术
    expect(passthrough.content).toBe('AI服务暂时不可用，请稍后重试');

    const noCategory = reduceFrozen(state, { type: 'error', code: 500, error: '后端错误' });
    expect(noCategory.errorCategory).toBeUndefined();
    expect(noCategory.content).toBe('后端错误');
  });

  it('⑥ 重复 start 不新建：generationId 与已生成内容保持不变', () => {
    const state = createAssistantMessage({ status: 'loading', content: '你' });
    const started = reduceFrozen(state, { type: 'start', generationId: 'gen-1', conversationId: 'conv-1' });
    const restarted = reduceFrozen(started, { type: 'start', generationId: 'gen-1' });

    expect(restarted.generationId).toBe('gen-1');
    expect(restarted.conversationId).toBe('conv-1');
    expect(restarted.content).toBe('你');
    expect(restarted.status).toBe('loading');
  });

  it('⑦ 乱序 chunk 按到达顺序拼接（reducer 不做重排）', () => {
    const state = createAssistantMessage();
    let next = reduceFrozen(state, { type: 'start', generationId: 'gen-1' });

    for (const text of ['B', 'A', 'C']) {
      next = reduceFrozen(next, { type: 'chunk', chunk: text });
    }

    expect(next.content).toBe('BAC');
    expect(next.status).toBe('loading');
  });
});
