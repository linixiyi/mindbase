import { describe, expect, it } from 'vitest';
import { type ChatStreamFrame, type ChatStreamState, reduceStreamFrame } from '../reducer';

/** task-0004 停止语义：stop 帧收敛为 stopped、终态守卫、completion 不回退 stopped */
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

/** 冻结入参后归约：reducer 修改入参会在 strict mode 抛 TypeError； 归约前后对比深拷贝快照，兜底断言入参未被修改。 */
function reduceFrozen(state: ChatStreamState, frame: ChatStreamFrame) {
  const stateSnapshot = structuredClone(state);
  const frameSnapshot = structuredClone(frame);
  const next = reduceStreamFrame(deepFreeze(state), deepFreeze(frame));
  expect(state).toEqual(stateSnapshot);
  expect(frame).toEqual(frameSnapshot);
  return next;
}

describe('reduceStreamFrame：停止语义（task-0004）', () => {
  it('① 生成中 stop 帧：status 收敛为 stopped 且 content 不变', () => {
    const state = createAssistantMessage({ generationId: 'gen-1', status: 'loading', content: '已生成的部分回答' });
    const next = reduceFrozen(state, { type: 'stop' });

    expect(next.status).toBe('stopped');
    expect(next.content).toBe('已生成的部分回答');
  });

  it('② 正常 completion：status 收敛为 finished（停止路径之外的原语义不变）', () => {
    const state = createAssistantMessage({ generationId: 'gen-1', status: 'loading', content: '完整回答' });
    const next = reduceFrozen(state, { type: 'completion', status: 'finished' });

    expect(next.status).toBe('finished');
    expect(next.content).toBe('完整回答');
  });

  it('③ error 帧不被 stop 覆盖：已是 error 的消息收到 stop 保持 error 与原正文', () => {
    const errored = reduceFrozen(
      createAssistantMessage({ generationId: 'gen-1', status: 'loading', content: '生成中' }),
      { type: 'error', code: 500, error: '后端错误' }
    );
    expect(errored.status).toBe('error');
    expect(errored.content).toBe('后端错误');

    const afterStop = reduceFrozen(errored, { type: 'stop' });
    expect(afterStop.status).toBe('error');
    expect(afterStop.content).toBe('后端错误');
  });

  it('④ stopped 后重复 completion：不把已停止的消息改回 finished', () => {
    const stopped = reduceFrozen(
      createAssistantMessage({ generationId: 'gen-1', status: 'loading', content: '已生成的部分回答' }),
      { type: 'stop' }
    );
    expect(stopped.status).toBe('stopped');

    const afterLateCompletion = reduceFrozen(stopped, { type: 'completion', status: 'finished' });
    expect(afterLateCompletion.status).toBe('stopped');
    expect(afterLateCompletion.content).toBe('已生成的部分回答');
  });
});
