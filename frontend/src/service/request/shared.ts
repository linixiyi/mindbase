import { useAuthStore } from '@/store/modules/auth';
import { localStg } from '@/utils/storage';
import { fetchRefreshToken } from '../api';
import type { RequestInstanceState } from './type';

const backendMessageMap: Record<string, string> = {
  INVITE_CODE_REQUIRED: '请输入邀请码',
  INVITE_CODE_INVALID: '邀请码不正确，请检查后重试',
  INVITE_CODE_EXPIRED: '邀请码已过期，请重新获取',
  INVITE_CODE_EXHAUSTED: '邀请码已失效，请重新获取',
  REGISTRATION_CLOSED: '当前暂未开放注册',
  'Username already exists': '用户名已存在'
};

export function getAuthorization() {
  const token = localStg.get('token');
  const Authorization = token ? `Bearer ${token}` : null;

  return Authorization;
}

export function normalizeBackendMessage(message: string) {
  return backendMessageMap[message] || message;
}

/** 网络/超时类英文技术串 → 中文分层文案（pm-022）：只重写 axios 默认技术串，后端业务文案原样放行 */
export function normalizeTransportErrorMessage(
  message: string,
  error: { code?: string; response?: { status?: number } | null }
) {
  const raw = message || '';

  // 超时：ECONNABORTED 或 axios 默认的 "timeout of 10000ms exceeded"
  if (error.code === 'ECONNABORTED' || /^timeout of \d+ms exceeded/i.test(raw)) {
    return '网络请求超时，请检查网络后重试';
  }
  // 断网/DNS 失败：ERR_NETWORK 或 axios 默认的 "Network Error"
  if (error.code === 'ERR_NETWORK' || /^network error$/i.test(raw)) {
    return '网络连接异常，请检查网络后重试';
  }
  // HTTP 状态失败且后端没有给出业务文案时，按状态分层兜底
  const statusMatch = /^Request failed with status code (\d+)$/i.exec(raw);
  if (statusMatch) {
    const status = Number(statusMatch[1]);
    if (status >= 500) {
      return '服务器暂时不可用，请稍后重试';
    }
    return '请求失败，请稍后重试';
  }
  // @sa/axios 对"HTTP 200 但业务码失败"构造的技术串，业务文案缺失时兜底
  if (/^the backend request error$/i.test(raw)) {
    return '请求失败，请稍后重试';
  }
  return raw;
}

/** refresh token */
async function handleRefreshToken() {
  const authStore = useAuthStore();
  const { resetStore } = authStore;

  const rToken = localStg.get('refreshToken') || '';
  const { error, data } = await fetchRefreshToken(rToken);
  if (!error) {
    authStore.setToken(data.token);
    localStg.set('refreshToken', data.refreshToken);
    return true;
  }

  resetStore();

  return false;
}

export async function handleExpiredRequest(state: RequestInstanceState) {
  if (!state.refreshTokenFn) {
    state.refreshTokenFn = handleRefreshToken();
  }

  const success = await state.refreshTokenFn;

  setTimeout(() => {
    state.refreshTokenFn = null;
  }, 1000);

  return success;
}

export function showErrorMsg(state: RequestInstanceState, message: string) {
  if (!state.errMsgStack?.length) {
    state.errMsgStack = [];
  }

  const isExist = state.errMsgStack.includes(message);

  if (!isExist) {
    state.errMsgStack.push(message);

    window.$message?.error(message, {
      onLeave: () => {
        state.errMsgStack = state.errMsgStack.filter(msg => msg !== message);

        setTimeout(() => {
          state.errMsgStack = [];
        }, 5000);
      }
    });
  }
}
