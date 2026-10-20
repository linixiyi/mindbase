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
  <!-- Quiet ground: the page sits on the layout token, the card is an opaque panel with a hairline -->
  <div class="relative size-full flex-center bg-layout">
    <NCard
      :bordered="false"
      class="relative z-4 w-auto card-wrapper"
      :style="{ background: 'rgb(var(--container-bg-color))' }"
    >
      <div :class="isRegisterModule ? 'login-panel login-panel--register' : 'login-panel'">
        <header class="flex-y-center justify-between">
          <SystemLogo class="text-64px text-primary lt-sm:text-48px" />
          <h3 class="flex-y-center gap-2 text-28px text-base-text font-600 lh-1.25 lt-sm:text-22px">
            <span>{{ $t('system.title') }}</span>
            <span v-if="isRegisterModule" class="text-18px text-secondary font-500 lt-sm:text-16px">
              {{ $t(activeModule.label) }}
            </span>
          </h3>
          <div class="i-flex-col">
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
        </header>
        <main class="pt-24px">
          <h3 v-if="!isRegisterModule" class="text-17px text-base-text font-600 lh-1.4">
            {{ $t(activeModule.label) }}
          </h3>
          <div class="pt-24px">
            <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
              <component :is="activeModule.component" />
            </Transition>
          </div>
        </main>
      </div>
    </NCard>
  </div>
</template>

<style scoped>
.login-panel {
  width: 400px;
}

.login-panel--register {
  width: min(860px, calc(100vw - 72px));
}

@media (max-width: 640px) {
  .login-panel,
  .login-panel--register {
    width: 300px;
  }
}
</style>
