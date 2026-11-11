import { describe, expect, it } from 'vitest';
import { createHistoryLoader } from '../historyLoader';
import type { HistoryFetcher } from '../historyLoader';

interface Message {
  id: number;
  content: string;
}

let nextMessageId = 1;
function msg(content: string): Message {
  nextMessageId += 1;
  return { id: nextMessageId, content };
}

interface FetchCall {
  cid: string;
  signal: AbortSignal;
}

/** 可手工结算的 fake fetcher：记录每次调用的 cid 与 signal，按调用序号定向 resolve/reject。 同一 cid 可能被多次加载（切换后再切回），按序号定向避免同名 cid 互相覆盖结算句柄。 */
function createDeferredFetcher() {
  const calls: FetchCall[] = [];
  const resolvers: ((data: Message[]) => void)[] = [];
  const rejecters: ((error: unknown) => void)[] = [];

  const fetcher: HistoryFetcher<Message[]> = (cid, signal) => {
    calls.push({ cid, signal });
    return new Promise<Message[]>((resolve, reject) => {
      resolvers.push(resolve);
      rejecters.push(reject);
    });
  };

  return {
    fetcher,
    calls,
    resolve: (index: number, data: Message[]) => resolvers[index]?.(data),
    reject: (index: number, error: unknown) => rejecters[index]?.(error)
  };
}

describe('createHistoryLoader：历史加载单源 + 在途取消（task-0003）', () => {
  it('① 并发两次同 cid 只调 1 次（在途去重）', async () => {
    const f = createDeferredFetcher();
    const loader = createHistoryLoader<Message[]>(f.fetcher);

    const p1 = loader.load('c1');
    const p2 = loader.load('c1');

    // 在途期间：fetcher 只被调用一次，两个调用方共享同一笔请求与同一 signal
    expect(f.calls).toHaveLength(1);
    expect(f.calls[0]?.cid).toBe('c1');
    expect(p1).toBe(p2);

    f.resolve(0, [msg('第一条'), msg('第二条')]);
    const [r1, r2] = await Promise.all([p1, p2]);
    expect(r1.aborted).toBe(false);
    expect(r1.error).toBeNull();
    expect(r1.data?.map(m => m.content)).toEqual(['第一条', '第二条']);
    expect(r2).toEqual(r1);

    // 在途结束后再 load：去重不吞新请求，重新调用 fetcher
    const p3 = loader.load('c1');
    expect(f.calls).toHaveLength(2);
    f.resolve(1, []);
    const r3 = await p3;
    expect(r3).toEqual({ data: [], error: null, aborted: false });
  });

  it('② 切 cid 后旧请求被 abort 且旧结果不提交', async () => {
    const f = createDeferredFetcher();
    const loader = createHistoryLoader<Message[]>(f.fetcher);

    const p1 = loader.load('c1');
    const p2 = loader.load('c2');

    // 旧请求的 signal 已触发，新请求的 signal 未触发
    expect(f.calls).toHaveLength(2);
    expect(f.calls[0]?.cid).toBe('c1');
    expect(f.calls[0]?.signal.aborted).toBe(true);
    expect(f.calls[1]?.cid).toBe('c2');
    expect(f.calls[1]?.signal.aborted).toBe(false);

    // 旧 fetcher 无视 signal、迟到成功：结果必须标记 aborted 且不携带数据（不可提交）
    f.resolve(0, [msg('过期会话的内容')]);
    const r1 = await p1;
    expect(r1).toEqual({ data: null, error: null, aborted: true });

    // 最新 cid 的结果正常提交
    f.resolve(1, [msg('新会话的内容')]);
    const r2 = await p2;
    expect(r2.data?.map(m => m.content)).toEqual(['新会话的内容']);
    expect(r2.error).toBeNull();
    expect(r2.aborted).toBe(false);
  });

  it('③ fetcher reject 时 error 可见且不携带可覆盖现有消息的数据', async () => {
    const f = createDeferredFetcher();
    const loader = createHistoryLoader<Message[]>(f.fetcher);

    const p = loader.load('c1');
    f.reject(0, new Error('网络连接失败'));
    const r = await p;

    // 错误可见：error 为归一化 Error，且不是 abort
    expect(r.aborted).toBe(false);
    expect(r.error).toBeInstanceOf(Error);
    expect(r.error?.message).toBe('网络连接失败');
    // data 恒为 null：调用方拿不到任何可以覆盖现有消息列表的数据
    expect(r.data).toBeNull();

    // 非 Error 值的 reject 也归一化为 Error，不抛出异常
    const f2 = createDeferredFetcher();
    const loader2 = createHistoryLoader<Message[]>(f2.fetcher);
    const p2 = loader2.load('c1');
    f2.reject(0, 'boom');
    const r2 = await p2;
    expect(r2.error).toBeInstanceOf(Error);
    expect(r2.error?.message).toBe('boom');
    expect(r2.data).toBeNull();
    expect(r2.aborted).toBe(false);
  });

  it('④ abort 不算错误（AbortError 与 ERR_CANCELED 形态均 error=null）', async () => {
    // DOM AbortError 形态：fetcher 感知 signal，被 abort 后以 AbortError reject
    const f = createDeferredFetcher();
    const loader = createHistoryLoader<Message[]>(f.fetcher);

    const p1 = loader.load('c1');
    const p2 = loader.load('c2');
    f.reject(0, new DOMException('The operation was aborted', 'AbortError'));
    const r1 = await p1;
    expect(r1).toEqual({ data: null, error: null, aborted: true });

    // axios 取消形态：code ERR_CANCELED 同样不算错误
    const f2 = createDeferredFetcher();
    const loader2 = createHistoryLoader<Message[]>(f2.fetcher);
    const q1 = loader2.load('c1');
    loader2.load('c2');
    f2.reject(0, Object.assign(new Error('canceled'), { code: 'ERR_CANCELED' }));
    const rq1 = await q1;
    expect(rq1).toEqual({ data: null, error: null, aborted: true });

    // abort 不影响最新一笔的正常提交
    f.resolve(1, [msg('切换后正常返回')]);
    const r2 = await p2;
    expect(r2.aborted).toBe(false);
    expect(r2.data?.[0]?.content).toBe('切换后正常返回');
  });

  it('⑤ 切 c1→c2→c1：旧 c1 迟到结算不破坏新 c1 请求的去重与可 abort 性', async () => {
    const f = createDeferredFetcher();
    const loader = createHistoryLoader<Message[]>(f.fetcher);

    const p1 = loader.load('c1');
    const p2 = loader.load('c2');
    const p3 = loader.load('c1');

    // 三笔请求各自独立；c1 两笔的 signal 均已被触发
    expect(f.calls).toHaveLength(3);
    expect(f.calls[0]?.signal.aborted).toBe(true);
    expect(f.calls[1]?.signal.aborted).toBe(true);
    expect(f.calls[2]?.cid).toBe('c1');
    expect(f.calls[2]?.signal.aborted).toBe(false);

    // 旧 c1 迟到成功：aborted，且不影响新 c1
    f.resolve(0, [msg('旧')]);
    const r1 = await p1;
    expect(r1.aborted).toBe(true);

    // c2 迟到成功：同样 aborted（它的 signal 已在切回 c1 时被触发）
    f.resolve(1, [msg('过期 c2')]);
    const r2 = await p2;
    expect(r2.aborted).toBe(true);

    // 最新 c1 正常提交；提交后再 load 同 cid 会重新发请求（去重状态已正确清理）
    f.resolve(2, [msg('新')]);
    const r3 = await p3;
    expect(r3.aborted).toBe(false);
    expect(r3.data?.[0]?.content).toBe('新');

    const p4 = loader.load('c1');
    expect(f.calls).toHaveLength(4);
    f.resolve(3, [msg('再次加载')]);
    const r4 = await p4;
    expect(r4.aborted).toBe(false);
  });
});
