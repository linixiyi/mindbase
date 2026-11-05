import { createLocalforage, createStorage } from '@sa/utils';

const storagePrefix = import.meta.env.VITE_STORAGE_PREFIX || '';

export const localStg = createStorage<StorageType.Local>('local', storagePrefix);

export const sessionStg = createStorage<StorageType.Session>('session', storagePrefix);

export const localforage = createLocalforage<StorageType.Local>('local');

/**
 * pm-002 一次性迁移：旧版 rememberedLogin 可能含明文密码，就地剥离重写， 避免明文密码留在 localStorage。应用启动（main.ts）与登录页挂载（pwd-login.vue）共用这一份逻辑，
 * 老用户不回访登录页也会在启动时被清。
 */
export function migrateRememberedLogin() {
  const remembered = localStg.get('rememberedLogin');
  if (remembered && 'password' in remembered) {
    localStg.set('rememberedLogin', { userName: remembered.userName });
  }
  return remembered;
}
