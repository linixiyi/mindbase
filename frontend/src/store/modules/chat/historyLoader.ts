/**
 * 历史消息加载单源（task-0003）：此前 store.loadMessages 与 chat-list.getList 各有一套守卫， 切会话会对同一参数发两次 GET，且请求层没有取消机制，慢的旧响应可能覆盖新会话内容。
 * 这里把「会话历史加载」收敛为一个可注入 fetcher 的加载器：
 *
 * 契约：
 *
 * - 同 conversationId 在途去重：同一 cid 的并发 load 共享同一笔请求（fetcher 只调一次）；
 * - 切换 cid 时 abort 上一笔：旧请求的 AbortSignal 被触发，旧结果不再提交；
 * - 只有最新 cid 的结果可提交：过期的成功/失败一律标记 aborted=true 且 error=null 返回；
 * - 返回 { data, error, aborted }：aborted 表示「结果不可提交」，不算错误（abort 不是失败）；
 * - fetcher reject 时 error 为归一化的 Error，data 为 null。
 */

export type HistoryFetcher<T> = (conversationId: string, signal: AbortSignal) => Promise<T>;

export interface HistoryLoadResult<T> {
  /** 仅当 !error && !aborted 且 fetcher 正常返回时携带数据，否则为 null */
  data: T | null;
  /** 仅当 fetcher reject 且非 abort 时携带，aborted 时恒为 null（abort 不算错误） */
  error: Error | null;
  /** true 表示该笔结果不可提交：被更新的 load 取代（切换会话）或空 cid 无请求 */
  aborted: boolean;
}

export interface HistoryLoader<T> {
  load: (conversationId: string) => Promise<HistoryLoadResult<T>>;
}

/** abort 请求的两种典型形态：DOM AbortError（fetcher 自己感知 signal）与 axios ERR_CANCELED */
function isAbortLikeError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const candidate = error as { name?: string; code?: string };
  return candidate.name === 'AbortError' || candidate.code === 'ERR_CANCELED' || candidate.code === 'ECANCELED';
}

function toError(error: unknown): Error {
  if (error instanceof Error) {
    return error;
  }
  return new Error(typeof error === 'string' ? error : '加载历史消息失败');
}

export function createHistoryLoader<T>(fetcher: HistoryFetcher<T>): HistoryLoader<T> {
  /** 在途请求的 conversationId；null 表示当前无在途请求 */
  let inFlightCid: string | null = null;
  let controller: AbortController | null = null;
  /** 同 cid 并发去重：所有调用方共享的同一笔在途 Promise */
  let inFlight: Promise<HistoryLoadResult<T>> | null = null;

  /** 结算时清理在途状态。必须同时匹配 cid 与 controller：切 c1→c2→c1 后，旧 c1 一笔的 迟到结算不能误清新 c1 请求的在途状态（否则新请求失去去重与可 abort 性） */
  function clearInFlight(cid: string, ctrl: AbortController) {
    if (inFlightCid === cid && controller === ctrl) {
      inFlightCid = null;
      controller = null;
      inFlight = null;
    }
  }

  function load(conversationId: string): Promise<HistoryLoadResult<T>> {
    if (!conversationId) {
      // 空 cid 不发请求；aborted=true 语义为「没有可提交的结果」
      return Promise.resolve({ data: null, error: null, aborted: true });
    }

    // ① 同 conversationId 在途去重：直接共享同一笔请求
    if (inFlightCid === conversationId && inFlight) {
      return inFlight;
    }

    // ② 切换会话：abort 上一笔。被中止的一笔稍后以 aborted=true 结算，不提交结果
    controller?.abort();

    const currentController = new AbortController();
    controller = currentController;
    inFlightCid = conversationId;
    const requestCid = conversationId;

    inFlight = (async (): Promise<HistoryLoadResult<T>> => {
      try {
        const data = await fetcher(requestCid, currentController.signal);
        // ③ 只有最新 cid 的结果可提交：fetcher 忽略 signal 迟到成功时同样拦下
        // （signal.aborted 在任何被取代场景下必为 true；controller 比对是双保险）
        if (inFlightCid !== requestCid || controller !== currentController || currentController.signal.aborted) {
          return { data: null, error: null, aborted: true };
        }
        clearInFlight(requestCid, currentController);
        return { data, error: null, aborted: false };
      } catch (error) {
        clearInFlight(requestCid, currentController);
        // ④ abort 不算错误：signal 已触发或错误本身是 abort 形态时，error 保持 null
        if (currentController.signal.aborted || isAbortLikeError(error)) {
          return { data: null, error: null, aborted: true };
        }
        return { data: null, error: toError(error), aborted: false };
      }
    })();

    return inFlight;
  }

  return { load };
}
