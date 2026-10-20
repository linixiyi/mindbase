<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
import ConversationSidebar from './modules/conversation-sidebar.vue';
import ReferencePreviewPage from './modules/reference-preview-page.vue';

const route = useRoute();
const showReferencePreview = computed(() => route.query.preview === 'reference');
const appStore = useAppStore();
const sidebarCollapsed = ref(appStore.isMobile);
watch(
  () => appStore.isMobile,
  mobile => {
    if (mobile) sidebarCollapsed.value = true;
  }
);
</script>

<template>
  <div v-if="showReferencePreview" class="h-full">
    <ReferencePreviewPage />
  </div>
  <div v-else class="h-full bg-layout p-4">
    <div class="chat-surface relative h-full flex overflow-hidden bg-container">
      <button
        v-if="appStore.isMobile && !sidebarCollapsed"
        class="sidebar-backdrop"
        aria-label="收起对话列表"
        @click="sidebarCollapsed = true"
      />
      <ConversationSidebar
        v-model:collapsed="sidebarCollapsed"
        :class="{ 'mobile-conversations': appStore.isMobile }"
      />
      <div class="relative min-w-0 flex flex-col flex-1">
        <button
          v-show="sidebarCollapsed"
          class="conversation-toggle absolute left-3 top-3 z-20 h-9 w-9 inline-flex items-center justify-center rounded-10px"
          aria-label="展开对话列表"
          @click="sidebarCollapsed = false"
        >
          <icon-material-symbols:left-panel-open-outline-rounded class="text-18px" />
        </button>
        <ChatList />
        <InputBox />
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 聊台画布：24px 圆角、发丝线描边、零阴影（DESIGN.md › Shapes / Elevation） */
.chat-surface {
  border: 1px solid var(--app-border);
  border-radius: 24px;
}
.conversation-toggle {
  background: var(--app-material-nav, var(--app-surface));
  backdrop-filter: var(--app-material-filter, saturate(180%) blur(20px));
  color: var(--app-muted);
  border: 1px solid var(--app-border);
  box-shadow: var(--app-shadow-e1);
  cursor: pointer;
  transition:
    color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    background-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);
}
.conversation-toggle:hover {
  color: rgb(var(--primary-color));
  background: var(--app-fill-hover, var(--app-subtle));
}
.conversation-toggle:focus-visible {
  outline: 2px solid rgb(var(--primary-color) / 0.6);
  outline-offset: 2px;
}
.mobile-conversations {
  position: absolute;
  inset: 0 auto 0 0;
  z-index: 30;
  background: var(--app-material-nav, var(--app-surface));
  backdrop-filter: var(--app-material-filter, saturate(180%) blur(20px));
}
.sidebar-backdrop {
  position: absolute;
  inset: 0;
  z-index: 25;
  background: var(--app-scrim, rgb(0 0 0 / 0.3));
  border: 0;
  cursor: pointer;
}
</style>
