<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import PwdLogin from './modules/pwd-login.vue';
import CodeLogin from './modules/code-login.vue';
import Register from './modules/register.vue';
import ResetPwd from './modules/reset-pwd.vue';
import BindWechat from './modules/bind-wechat.vue';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

const props = defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();

interface LoginModule {
  label: string;
  component: Component;
}

const moduleMap: Record<UnionKey.LoginModule, LoginModule> = {
  'pwd-login': { label: loginModuleRecord['pwd-login'], component: PwdLogin },
  'code-login': { label: loginModuleRecord['code-login'], component: CodeLogin },
  register: { label: loginModuleRecord.register, component: Register },
  'reset-pwd': { label: loginModuleRecord['reset-pwd'], component: ResetPwd },
  'bind-wechat': { label: loginModuleRecord['bind-wechat'], component: BindWechat }
};

const activeModule = computed(() => moduleMap[props.module || 'pwd-login']);
const isRegisterModule = computed(() => (props.module || 'pwd-login') === 'register');
</script>

<template>
  <!--
 Airy 登录：去盒——表单直接坐在页面上；亮色白底大留白，暗色落回 layout 地面；
       淡蓝只出现在品牌标与主行动上（DESIGN.md › 一线蓝律） 
-->
  <div class="login-page relative size-full">
    <div class="login-tools absolute right-24px top-24px z-4 flex-y-center gap-16px">
      <ThemeSchemaSwitch
        :theme-schema="themeStore.themeScheme"
        :show-tooltip="false"
        class="text-20px lt-sm:text-18px"
        @switch="themeStore.toggleThemeScheme"
      />
      <LangSwitch
        v-if="themeStore.header.multilingual.visible"
        :lang="appStore.locale"
        :lang-options="appStore.localeOptions"
        :show-tooltip="false"
        @change-lang="appStore.changeLocale"
      />
    </div>
    <div class="h-full overflow-auto">
      <div class="login-flow min-h-full flex flex-col pb-48px pt-64px lt-sm:pb-32px lt-sm:pt-56px">
        <div :class="isRegisterModule ? 'login-panel login-panel--register' : 'login-panel'" class="m-auto">
          <header class="login-head">
            <SystemLogo class="login-head__logo" />
            <h1 class="login-head__title">
              <span>{{ $t('system.title') }}</span>
              <span v-if="isRegisterModule" class="login-head__module">{{ $t(activeModule.label) }}</span>
            </h1>
          </header>
          <main class="pt-40px">
            <h2 v-if="!isRegisterModule" class="login-module-title">{{ $t(activeModule.label) }}</h2>
            <div class="pt-24px">
              <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
                <component :is="activeModule.component" />
              </Transition>
            </div>
          </main>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 地面：亮色白底（container 即纯白），暗色落回 layout 近黑地面——全部经主题 token，无硬编码 */
.login-page {
  background: rgb(var(--container-bg-color));
}

html.dark .login-page {
  background: rgb(var(--layout-bg-color));
}

.login-panel {
  width: 400px;
}

.login-panel--register {
  width: min(860px, calc(100vw - 72px));
}

/* m-auto 居中：内容高于视口时随滚动展开，不裁顶 */
.login-flow {
  padding-left: 24px;
  padding-right: 24px;
}

/* 居中竖排 hero：品牌标（淡蓝）+ Display 32 页题 */
.login-head {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  text-align: center;
}

.login-head__logo {
  font-size: 56px;
  color: rgb(var(--primary-color));
}

.login-head__title {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: center;
  gap: 4px 12px;
  margin: 0;
  font-size: 32px;
  font-weight: 600;
  line-height: 1.2;
  color: rgb(var(--base-text-color));
}

.login-head__module {
  font-size: 16px;
  font-weight: 500;
  color: var(--app-text-secondary);
}

/* 区块题：Headline 20/600 */
.login-module-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  text-align: center;
  color: rgb(var(--base-text-color));
}

@media (max-width: 640px) {
  .login-panel,
  .login-panel--register {
    width: calc(100vw - 48px);
  }
}
</style>
