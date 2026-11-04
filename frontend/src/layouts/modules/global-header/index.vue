<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import type { ComponentPublicInstance } from 'vue';
import { useRouter } from 'vue-router';
import { useFullscreen } from '@vueuse/core';
import { LAYOUT_SCROLL_EL_ID } from '@sa/materials';
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
const router = useRouter();
const { isFullscreen, toggle } = useFullscreen();

const isDev = import.meta.env.DEV;

/**
 * Frosted navigation material (DESIGN.md › Material Vocabulary › nav-material). R12 overdrive: the header rides the BAR
 * tier of the three-step refraction ladder (--app-glass-filter-bar — the calmest pool; the command palette keeps the
 * top tier). The edge light is the shared anisotropic rim ring (.app-glass-rim::before) with the bottom stop suppressed
 * here — the bar's bottom edge belongs to the scroll-revealed hairline, so every edge is declared once.
 */
const navMaterialStyle = {
  background: 'var(--app-material-nav)',
  WebkitBackdropFilter: 'var(--app-glass-filter-bar)',
  backdropFilter: 'var(--app-glass-filter-bar)'
};

/**
 * R12 overdrive: the scroll-reveal hairline (DESIGN.md §18 › 顶栏滚动渐显发丝线). The 0.5px line under the bar reports whether
 * content is being cut at the header line: at rest the page is not cutting anything and the bar floats as pure glass
 * (line hidden); once anything scrolls under the header's bottom edge the line fades in (250ms). Progressively enhanced
 * — no JS or prefers-reduced-motion keeps the CSS default (line always visible), so the separator never depends on the
 * listener.
 *
 * The signal is rAF-throttled and read from the layout's own scroll element (`#__SCROLL_EL_ID__`, 'content' scroll
 * mode). One extra case matters: a NESTED scroller that sits flush against the header line (the chat canvas's message
 * scrollbar — messages vanish exactly at the bar's bottom edge) also counts as content cut there; any scroller lower on
 * the page cuts at its own boundary and is none of the header's business. Scroll events don't bubble, but they are
 * captured at document level with { capture: true } — one passive listener covers everything.
 */
const headerRef = ref<ComponentPublicInstance | null>(null);
let detachHairline: (() => void) | null = null;

function setupHeaderHairline() {
  // $el can be a fragment anchor (comment/text node) when a child component's template keeps a
  // non-element root node in dev — it has no classList, so only accept a real element and let the
  // hairline degrade to its CSS default (progressive enhancement) instead of throwing.
  const root = headerRef.value?.$el;
  const header = root instanceof HTMLElement ? root : undefined;
  const main = document.getElementById(LAYOUT_SCROLL_EL_ID);
  if (!header || !main) return;

  let frame = 0;
  let lastTarget: HTMLElement | null = null;

  const evaluate = () => {
    frame = 0;
    let cut = main.scrollTop > 0;
    if (!cut && lastTarget && lastTarget !== main && lastTarget.isConnected) {
      const line = main.getBoundingClientRect().top;
      cut = lastTarget.scrollTop > 0 && Math.abs(lastTarget.getBoundingClientRect().top - line) <= 2;
    }
    header.classList.toggle('glass-bar--at-top', !cut);
  };

  const scheduleEvaluate = () => {
    if (!frame) frame = requestAnimationFrame(evaluate);
  };

  const onScroll = (event: Event) => {
    lastTarget = event.target as HTMLElement;
    scheduleEvaluate();
  };

  document.addEventListener('scroll', onScroll, { capture: true, passive: true });
  // route changes reset the main scroller silently (scrollTo a no-op when already at 0 leaves the
  // chat scrollbar as a stale lastTarget) — re-evaluate once after every navigation
  const removeAfterEach = router.afterEach(() => {
    nextTick(scheduleEvaluate);
  });
  evaluate();

  detachHairline = () => {
    if (frame) cancelAnimationFrame(frame);
    document.removeEventListener('scroll', onScroll, { capture: true } as EventListenerOptions);
    removeAfterEach();
  };
}

onMounted(async () => {
  // reduced motion: the hairline stays always-visible (the fade is the enhancement, not the signal)
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  // the admin layout mounts <main> after the header slot — wait for the full patch
  await nextTick();
  setupHeaderHairline();
});

onBeforeUnmount(() => {
  detachHairline?.();
  detachHairline = null;
});

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
  <DarkModeContainer
    ref="headerRef"
    class="app-glass-rim glass-bar h-full flex-y-center justify-between gap-2 px-4"
    :style="navMaterialStyle"
  >
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

<style scoped lang="scss">
/* R12 overdrive: the header's bottom hairline (DESIGN.md §18 › 顶栏滚动渐显发丝线).
   A 0.5px line at the bar's bottom edge, colored by --app-header-hairline (the values the old
   settings.ts shadow token carried). CSS default is visible — no JS and prefers-reduced-motion
   keep it that way (常显); the rAF listener above adds .glass-bar--at-top while the page rests
   at its top, letting the line fade in only when content is actually being cut at the header.
   The rim ring's bottom stop is suppressed here so the bottom edge is declared exactly once. */
.glass-bar {
  /* app-glass-rim already supplies position: relative — it anchors the ::before ring and this ::after */
  --app-rim-bottom: transparent;
}

.glass-bar::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 0.5px;
  background: var(--app-header-hairline);
  opacity: 1;
  transition: opacity var(--app-dur-base) var(--app-ease-standard);
  pointer-events: none;
}

.glass-bar--at-top::after {
  opacity: 0;
}
</style>
