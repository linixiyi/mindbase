import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createStreamBuffer } from '../buffer';

interface FlushRecord {
  generationId: string;
  text: string;
}

function createRecorder() {
  const flushed: FlushRecord[] = [];
  const buffer = createStreamBuffer({
    flushIntervalMs: 50,
    onFlush: (generationId, text) => {
      flushed.push({ generationId, text });
    }
  });
  return { buffer, flushed };
}

describe('createStreamBuffer：chunk 批量 flush（task-0002）', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('① 同窗口 10 条 chunk 只触发 1 次 onFlush 且内容为完整拼接', () => {
    const { buffer, flushed } = createRecorder();

    for (let i = 0; i < 10; i += 1) {
      buffer.push('gen-1', `c${i}`);
    }
    // 窗口内只累积不输出
    expect(flushed).toHaveLength(0);

    vi.advanceTimersByTime(49);
    expect(flushed).toHaveLength(0);

    vi.advanceTimersByTime(1);
    expect(flushed).toEqual([{ generationId: 'gen-1', text: 'c0c1c2c3c4c5c6c7c8c9' }]);

    // 窗口已消费，不再重复输出
    vi.advanceTimersByTime(500);
    expect(flushed).toHaveLength(1);
  });

  it('② 跨窗口按时间顺序 flush', () => {
    const { buffer, flushed } = createRecorder();

    buffer.push('gen-1', '第一段');
    vi.advanceTimersByTime(50);
    buffer.push('gen-1', '第二段');
    vi.advanceTimersByTime(50);
    buffer.push('gen-1', '第三段');
    vi.advanceTimersByTime(50);

    expect(flushed).toEqual([
      { generationId: 'gen-1', text: '第一段' },
      { generationId: 'gen-1', text: '第二段' },
      { generationId: 'gen-1', text: '第三段' }
    ]);
  });

  it('③ completion 前 flush 剩余：立即输出、清空且不重复', () => {
    const { buffer, flushed } = createRecorder();

    buffer.push('gen-1', '已到达的部分');
    // 窗口未到期，尚未输出
    vi.advanceTimersByTime(30);
    expect(flushed).toHaveLength(0);

    // completion/stop/error 前手动 flush：剩余内容立即落盘
    buffer.flush();
    expect(flushed).toEqual([{ generationId: 'gen-1', text: '已到达的部分' }]);

    // flush 已随窗口清掉定时器，等待也不会重复输出
    vi.advanceTimersByTime(500);
    expect(flushed).toHaveLength(1);

    // 空缓冲上 flush 是安全的 no-op
    buffer.flush();
    expect(flushed).toHaveLength(1);
  });

  it('④ dispose 清理定时器且尾部不丢', () => {
    const { buffer, flushed } = createRecorder();

    buffer.push('gen-1', '尾巴内容');
    buffer.dispose();
    // 尾部立即输出，不丢字符
    expect(flushed).toEqual([{ generationId: 'gen-1', text: '尾巴内容' }]);

    // 定时器已被清理：等待再久也不重复输出
    vi.advanceTimersByTime(1000);
    expect(flushed).toHaveLength(1);

    // dispose 后不再接受新数据、不再开新窗口
    buffer.push('gen-1', '迟到数据');
    vi.advanceTimersByTime(1000);
    expect(flushed).toHaveLength(1);

    // 重复 dispose 是 no-op
    buffer.dispose();
    expect(flushed).toHaveLength(1);
  });

  it('⑤ flushIntervalMs=0 退化为逐条直通', () => {
    const flushed: FlushRecord[] = [];
    const buffer = createStreamBuffer({
      flushIntervalMs: 0,
      onFlush: (generationId, text) => {
        flushed.push({ generationId, text });
      }
    });

    buffer.push('gen-1', '你');
    buffer.push('gen-1', '好');
    buffer.push('gen-1', '呀');

    // 不等定时器：每条 push 立即输出一次，顺序与到达顺序一致
    expect(flushed).toEqual([
      { generationId: 'gen-1', text: '你' },
      { generationId: 'gen-1', text: '好' },
      { generationId: 'gen-1', text: '呀' }
    ]);

    vi.advanceTimersByTime(100);
    expect(flushed).toHaveLength(3);
  });
});
