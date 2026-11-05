import { request } from '../request';

/**
 * Login
 *
 * @param username User name
 * @param password Password
 */
export function fetchLogin(username: string, password: string) {
  return request<Api.Auth.LoginToken>({
    url: '/users/login',
    method: 'post',
    data: {
      username,
      password
    }
  });
}

export function fetchLogout() {
  return request({ url: '/users/logout', method: 'post' });
}

export function fetchRegister(username: string, password: string, inviteCode?: string) {
  return request({
    url: '/users/register',
    method: 'post',
    data: {
      username,
      password,
      inviteCode
    }
  });
}

/** 注册策略（pm-033）：匿名端点，注册页据此决定邀请码是否必填与提示文案 */
export type RegistrationPolicy = {
  mode: 'OPEN' | 'INVITE_ONLY' | 'CLOSED';
  inviteRequired: boolean;
};

export function fetchRegistrationPolicy() {
  return request<RegistrationPolicy>({ url: '/users/registration-policy', method: 'get' });
}

/** Get user info */
export function fetchGetUserInfo() {
  return request<Api.Auth.UserInfo>({ url: '/users/me' });
}

/**
 * Refresh token
 *
 * @param refreshToken Refresh token
 */
export function fetchRefreshToken(refreshToken: string) {
  return request<Api.Auth.LoginToken>({
    url: '/auth/refreshToken',
    method: 'post',
    data: {
      refreshToken
    }
  });
}

/**
 * return custom backend error
 *
 * @param code error code
 * @param msg error message
 */
export function fetchCustomBackendError(code: string, msg: string) {
  return request({ url: '/auth/error', params: { code, msg } });
}
