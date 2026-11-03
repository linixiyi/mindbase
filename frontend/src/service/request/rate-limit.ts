import { computed, ref } from 'vue';

/**
 * REST 429 全局限流倒计时（pm-022）：后端 429 响应体携带 retryAfterSeconds（UserController.buildRateLimitResponse），
 * 请求层在此收敛为秒级倒计时，登录等表单据此禁用按钮——与聊天 WS 429 已有倒计时同一体验。
 */
const rateLimitUntil = ref<number | null>(null);
const rateLimitRemainingSeconds = ref(0);
let rateLimitTimer: ReturnType<typeof setInterval> | null = null;

function clearRateLimitTimer() {
  if (rateLimitTimer !== null) {
    window.clearInterval(rateLimitTimer);
    rateLimitTimer = null;
  }
}

function syncRateLimitCountdown() {
  if (!rateLimitUntil.value) {
    rateLimitRemainingSeconds.value = 0;
    clearRateLimitTimer();
    return;
  }
  const remainingMs = rateLimitUntil.value - Date.now();
  rateLimitRemainingSeconds.value = Math.max(0, Math.ceil(remainingMs / 1000));
  if (remainingMs <= 0) {
    clearRateLimitTimer();
    rateLimitUntil.value = null;
  }
}

/** 429 响应到达时启动倒计时；retryAfterSeconds <= 0 或非数字时不启动 */
export function startRequestRateLimitCountdown(retryAfterSeconds: unknown) {
  const seconds = Math.ceil(Number(retryAfterSeconds));
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return;
  }
  rateLimitUntil.value = Date.now() + seconds * 1000;
  syncRateLimitCountdown();
  clearRateLimitTimer();
  rateLimitTimer = setInterval(syncRateLimitCountdown, 1000);
}

export const isRequestRateLimited = computed(() => rateLimitRemainingSeconds.value > 0);

export const requestRateLimitRemainingSeconds = computed(() => rateLimitRemainingSeconds.value);

/** 请求层统一入口（pm-022）：429 响应读取 retryAfterSeconds 并启动倒计时，其余响应不动 */
export function observeRateLimitResponse(response: { status?: number; data?: unknown } | null | undefined) {
  if (response?.status !== 429) {
    return;
  }
  const retryAfterSeconds = (response.data as Record<string, unknown> | undefined)?.retryAfterSeconds;
  startRequestRateLimitCountdown(retryAfterSeconds);
}
