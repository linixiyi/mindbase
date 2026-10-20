<script setup lang="ts">
import { computed } from 'vue';
import { GLOBAL_SIDER_MENU_ID } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import GlobalLogo from '../global-logo/index.vue';

defineOptions({
  name: 'GlobalSider'
});

const appStore = useAppStore();
const themeStore = useThemeStore();

const isVerticalMix = computed(() => themeStore.layout.mode === 'vertical-mix');
const isHorizontalMix = computed(() => themeStore.layout.mode === 'horizontal-mix');
const darkMenu = computed(() => !themeStore.darkMode && !isHorizontalMix.value && themeStore.sider.inverted);
const showLogo = computed(() => !isVerticalMix.value && !isHorizontalMix.value);
const menuWrapperClass = computed(() => (showLogo.value ? 'flex-1-hidden' : 'h-full'));

/** Frosted navigation material (DESIGN.md › Material Vocabulary › nav-material) */
const navMaterialStyle = {
  background: 'var(--app-material-nav)',
  WebkitBackdropFilter: 'var(--app-material-filter)',
  backdropFilter: 'var(--app-material-filter)'
};
</script>

<template>
  <!--
    Nav material: frosted glass clipped to the island's 24px radius. The elevation-island shadow
    lives on the AdminLayout aside (base-layout sider-class), not here — an inner shadow would
    double it. overflow-hidden clips the material to the radius.
  -->
  <DarkModeContainer
    class="size-full flex-col-stretch overflow-hidden rounded-24px"
    :inverted="darkMenu"
    :style="navMaterialStyle"
  >
    <GlobalLogo
      v-if="showLogo"
      :show-title="!appStore.siderCollapse"
      :style="{ height: themeStore.header.height + 'px' }"
    />
    <div :id="GLOBAL_SIDER_MENU_ID" :class="menuWrapperClass"></div>
  </DarkModeContainer>
</template>

<style scoped></style>
