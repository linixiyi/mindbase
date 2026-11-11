/**
 * 流式 chunk 批量缓冲（task-0002）：逐条 chunk 直写 assistant.content 会让 VueMarkdownIt 每帧整段重渲染，长答案 O(n²)。这里把同窗口（默认 50ms）内到达的 chunk
 * 合并成一次输出， 由 input-box.vue 在 onFlush 里做一次 reducer 更新。
 *
 * 契约：
 *
 * - push(generationId, text)：按 generationId 分桶累积，同窗口合并；窗口内不输出；
 * - flush()：立即输出全部剩余缓冲并清空（completion/stop/error 帧处理前必须先 flush， 保证字符不丢、拼接顺序不变）；
 * - dispose()：先输出剩余缓冲（尾部不丢），再清理定时器；之后 push 不再接受新数据。
 * - flushIntervalMs = 0 退化为逐条直通（push 即 flush），不回到任何逐帧重渲染之外的语义。
 */

export interface StreamBufferOptions {
  /** 合并窗口毫秒数，默认 50；0 表示逐条直通 */
  flushIntervalMs?: number;
  /** 输出回调：每次至多每个 generationId 一次，text 为该窗口内累计拼接结果 */
  onFlush: (generationId: string, text: string) => void;
}

export interface StreamBuffer {
  push: (generationId: string, text: string) => void;
  flush: () => void;
  dispose: () => void;
}

export function createStreamBuffer(options: StreamBufferOptions): StreamBuffer {
  const flushIntervalMs = options.flushIntervalMs ?? 50;
  const { onFlush } = options;

  /** 待输出缓冲：generationId → 累计文本。Map 迭代序即插入序，跨 generation 也按先到先出输出 */
  const pending = new Map<string, string>();
  let timer: ReturnType<typeof setTimeout> | null = null;
  let disposed = false;

  function clearTimer() {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  }

  /** 输出并清空全部缓冲；顺带清掉定时器（手动 flush 后窗口作废，下一次 push 重新开窗） */
  function outputPending() {
    clearTimer();
    if (pending.size === 0) {
      return;
    }
    const entries = [...pending.entries()];
    pending.clear();
    for (const [generationId, text] of entries) {
      if (text) {
        onFlush(generationId, text);
      }
    }
  }

  function push(generationId: string, text: string) {
    if (disposed || !text) {
      return;
    }
    pending.set(generationId, (pending.get(generationId) || '') + text);
    if (flushIntervalMs <= 0) {
      outputPending();
      return;
    }
    if (timer === null) {
      timer = setTimeout(outputPending, flushIntervalMs);
    }
  }

  function flush() {
    outputPending();
  }

  function dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    // 尾部不丢：先输出剩余缓冲，再确保定时器被清理
    outputPending();
    clearTimer();
  }

  return { push, flush, dispose };
}
