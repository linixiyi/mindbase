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

/**
 * Frosted navigation material (DESIGN.md › Material Vocabulary › nav-material). R12 overdrive: the island rides the
 * ISLAND tier of the three-step refraction ladder (--app-glass-filter-island), and the edge light is the shared
 * anisotropic rim ring (.app-glass-rim::before) on THIS element — the one that paints the glass. The island's soft
 * shadow lives on the AdminLayout aside; an inset rim there would sit beneath this translucent child and read muddy. No
 * drop shadow here — that would double the aside's elevation-island.
 */
const navMaterialStyle = {
  background: 'var(--app-material-nav)',
  WebkitBackdropFilter: 'var(--app-glass-filter-island)',
  backdropFilter: 'var(--app-glass-filter-island)'
};
</script>

<template>
  <!--
    Nav material: frosted glass clipped to the island's 24px radius. The elevation-island shadow
    lives on the AdminLayout aside (base-layout sider-class), not here — an inner shadow would
    double it; the anisotropic rim ring rides this element (.app-glass-rim). overflow-hidden
    clips the material to the radius.
  -->
  <DarkModeContainer
    class="app-glass-rim size-full flex-col-stretch overflow-hidden rounded-24px"
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
