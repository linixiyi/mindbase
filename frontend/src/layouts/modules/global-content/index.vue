<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { LAYOUT_SCROLL_EL_ID } from '@sa/materials';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { useRouteStore } from '@/store/modules/route';
import { useTabStore } from '@/store/modules/tab';

defineOptions({
  name: 'GlobalContent'
});

interface Props {
  /** Show padding for content */
  showPadding?: boolean;
}

withDefaults(defineProps<Props>(), {
  showPadding: true
});

const appStore = useAppStore();
const themeStore = useThemeStore();
const routeStore = useRouteStore();
const tabStore = useTabStore();
const router = useRouter();

const transitionName = computed(() => (themeStore.page.animate ? themeStore.page.animateMode : ''));

function resetScroll() {
  const el = document.querySelector(`#${LAYOUT_SCROLL_EL_ID}`);

  el?.scrollTo({ left: 0, top: 0 });
}

/* backlog#11 self-heal: a transient falsy in the Transition/KeepAlive slot (observed as a
 * comment-only content area while reloadFlag and the matched route are both fine — root
 * cause sits inside the Vue/Naive transition machinery) can leave a navigation permanently
 * blank because nothing re-renders afterwards. After each confirmed navigation, give the
 * enter phase 400ms, then if the content container still has no element child, remount the
 * current view via the app's own reloadFlag channel. See .spec/specs/2026-10-20_fix-nav-wedge. */
let healTimer: ReturnType<typeof setTimeout> | null = null;

function scheduleBlankHeal() {
  if (healTimer) clearTimeout(healTimer);
  healTimer = setTimeout(() => {
    healTimer = null;
    const scrollEl = document.querySelector(`#${LAYOUT_SCROLL_EL_ID}`);
    const contentEl = (scrollEl?.querySelector('main') as HTMLElement | null) ?? scrollEl;
    const empty = Boolean(contentEl) && !Array.from(contentEl!.children).some(child => child.nodeType === 1);

    // Either reloadFlag is stuck false, or it is true yet nothing rendered — the same
    // channel (flip reloadFlag off/on) remounts the current view either way.
    if (empty) {
      appStore.reloadPage(50);
    }
  }, 400);
}

const removeAfterEach = router.afterEach(() => {
  nextTick(() => scheduleBlankHeal());
});

onMounted(() => {
  scheduleBlankHeal();
});

onBeforeUnmount(() => {
  if (healTimer) clearTimeout(healTimer);
  removeAfterEach();
});
</script>

<template>
  <RouterView v-slot="{ Component, route }">
    <Transition
      :name="transitionName"
      mode="out-in"
      @before-leave="appStore.setContentXScrollable(true)"
      @after-leave="resetScroll"
      @after-enter="appStore.setContentXScrollable(false)"
    >
      <KeepAlive :include="routeStore.cacheRoutes" :exclude="routeStore.excludeCacheRoutes">
        <component
          :is="Component"
          v-if="appStore.reloadFlag"
          :key="tabStore.getTabIdByRoute(route)"
          :class="{ 'p-4 sm:p-6 xl:px-40px': showPadding }"
          class="flex-grow bg-layout transition-all duration-250 ease-[var(--app-ease-standard)]"
        />
      </KeepAlive>
    </Transition>
  </RouterView>
</template>

<style></style>
