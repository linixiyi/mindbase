import 'vue-markdown-shiki/style';
import markdownPlugin from '@/vendor/vue-markdown-shiki';
import './plugins/assets';
import { setupAppVersionNotification, setupDayjs, setupIconifyOffline, setupLoading, setupNProgress } from './plugins';
import { setupStore } from './store';
import { setupRouter } from './router';
import { setupI18n } from './locales';
import { migrateRememberedLogin } from './utils/storage';
import App from './App.vue';
async function setupApp() {
  // pm-002 一次性迁移：应用启动即清掉旧版 rememberedLogin 中的明文密码，老用户不回访登录页也能被清
  migrateRememberedLogin();

  setupLoading();

  setupNProgress();

  setupIconifyOffline();

  setupDayjs();

  const app = createApp(App);

  setupStore(app);

  await setupRouter(app);

  setupI18n(app);

  setupAppVersionNotification();

  app.use(markdownPlugin);

  app.mount('#app');
}

setupApp();
