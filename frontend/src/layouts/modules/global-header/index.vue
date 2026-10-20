<script setup lang="ts">
import { nextTick } from 'vue';
import { useFullscreen } from '@vueuse/core';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import GlobalSearch from '../global-search/index.vue';
import ThemeButton from './components/theme-button.vue';
import UserAvatar from './components/user-avatar.vue';

defineOptions({
  name: 'GlobalHeader'
});

interface Props {
  /** Whether to show the logo */
  // showLogo?: App.Global.HeaderProps['showLogo'];
  /** Whether to show the menu toggler */
  showMenuToggler?: App.Global.HeaderProps['showMenuToggler'];
  /** Whether to show the menu */
  // showMenu?: App.Global.HeaderProps['showMenu'];
}

defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();
const { isFullscreen, toggle } = useFullscreen();

const isDev = import.meta.env.DEV;

/** Frosted navigation material + bottom hairline (DESIGN.md › Material Vocabulary › nav-material) */
const navMaterialStyle = {
  background: 'var(--app-material-nav)',
  WebkitBackdropFilter: 'var(--app-material-filter)',
  backdropFilter: 'var(--app-material-filter)'
};

/**
 * Round 4 Azure: the theme switch is the one authorized View Transition — the new theme is revealed by a circle
 * expanding from the toggle (DESIGN.md › Motion & Delight › morph 边界律). Falls back to the plain toggle when the API is
 * absent or reduced motion is requested. The latch keeps one gesture to one toggle: the NTooltip/NButton chain can
 * deliver a click twice, which would skip a scheme step and double-run the morph.
 */
let themeSwitching = false;
async function handleThemeSchemaSwitch(event: MouseEvent) {
  if (themeSwitching) return;
  themeSwitching = true;
  setTimeout(() => {
    themeSwitching = false;
  }, 400);

  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  if (!document.startViewTransition || reducedMotion) {
    themeStore.toggleThemeScheme();
    return;
  }

  const { clientX, clientY } = event;
  const root = document.documentElement;
  root.style.setProperty('--theme-morph-x', `${clientX}px`);
  root.style.setProperty('--theme-morph-y', `${clientY}px`);
  root.style.setProperty(
    '--theme-morph-r',
    `${Math.hypot(Math.max(clientX, window.innerWidth - clientX), Math.max(clientY, window.innerHeight - clientY))}px`
  );

  await document.startViewTransition(async () => {
    themeStore.toggleThemeScheme();
    await nextTick();
  }).finished;
}
</script>

<template>
  <DarkModeContainer class="h-full flex-y-center justify-between gap-2 px-4 shadow-header" :style="navMaterialStyle">
    <div id="header-extra" class="h-full min-w-0 flex-col flex-1 justify-center"></div>
    <!-- <GlobalLogo v-if="showLogo" class="h-full" :style="{ width: themeStore.sider.width + 'px' }" /> -->
    <MenuToggler
      v-if="showMenuToggler && appStore.isMobile"
      :collapsed="appStore.siderCollapse"
      @click="appStore.toggleSiderCollapse"
    />
    <!--
    <div v-if="showMenu" :id="GLOBAL_HEADER_MENU_ID" class="h-full flex-y-center flex-1-hidden"></div>
    <div v-else class="h-full flex-y-center flex-1-hidden">
      <GlobalBreadcrumb v-if="!appStore.isMobile" class="ml-12px" />
    </div>
-->
    <div class="h-full flex-y-center shrink-0 justify-end">
      <GlobalSearch />
      <FullScreen v-if="!appStore.isMobile" :full="isFullscreen" @click="toggle" />
      <LangSwitch
        v-if="themeStore.header.multilingual.visible"
        :lang="appStore.locale"
        :lang-options="appStore.localeOptions"
        @change-lang="appStore.changeLocale"
      />
      <ThemeSchemaSwitch
        :theme-schema="themeStore.themeScheme"
        :is-dark="themeStore.darkMode"
        @switch="handleThemeSchemaSwitch"
      />
      <ThemeButton v-if="isDev" />
      <UserAvatar />
    </div>
  </DarkModeContainer>
</template>

<style scoped></style>
