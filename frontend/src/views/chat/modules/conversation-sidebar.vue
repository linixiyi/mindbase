<script setup lang="ts">
defineOptions({
  name: 'ConversationSidebar'
});

const collapsed = defineModel<boolean>('collapsed', { default: false });

const chatStore = useChatStore();
const { conversationId, sessionsLoading, filteredSessions, activeTab } = storeToRefs(chatStore);

onMounted(() => {
  chatStore.loadSessions();
});

function handleCollapse() {
  collapsed.value = true;
}

function handleNewChat() {
  chatStore.createNewSession();
}

function handleSelect(cid: string) {
  chatStore.switchSession(cid);
}

function handleArchive(cid: string) {
  chatStore.archiveSession(cid);
}

function handleUnarchive(cid: string) {
  chatStore.unarchiveSession(cid);
}

function setActiveTab(tab: 'active' | 'archived') {
  activeTab.value = tab;
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '';
  const date = dayjs(dateStr);
  const now = dayjs();
  if (date.isSame(now, 'day')) {
    return date.format('HH:mm');
  }
  if (date.isSame(now, 'week')) {
    return date.format('ddd');
  }
  if (date.isSame(now, 'year')) {
    return date.format('MM-DD');
  }
  return date.format('YYYY-MM-DD');
}
</script>

<template>
  <div
    class="chat-sidebar relative h-full flex flex-col shrink-0 overflow-hidden transition-[width]"
    :class="collapsed ? 'chat-sidebar--collapsed w-0 min-w-0' : 'w-[260px] min-w-[260px]'"
  >
    <div class="w-[260px] flex flex-col flex-1 overflow-hidden" :class="{ 'pointer-events-none invisible': collapsed }">
      <div class="flex items-center justify-between px-4 pb-2 pt-4">
        <span class="text-15px font-600">对话列表</span>
        <div class="flex items-center gap-1">
          <NButton type="primary" size="small" secondary @click="handleNewChat">
            <template #icon>
              <icon-material-symbols:add-rounded />
            </template>
            新对话
          </NButton>
          <NButton text size="tiny" @click="handleCollapse">
            <template #icon>
              <icon-material-symbols:left-panel-close-outline-rounded />
            </template>
          </NButton>
        </div>
      </div>

      <!-- Tabs -->
      <div class="chat-sidebar__tabs mx-4 mb-2 flex">
        <button
          type="button"
          class="chat-sidebar__tab"
          :class="{ 'chat-sidebar__tab--active': activeTab === 'active' }"
          @click="setActiveTab('active')"
        >
          活跃
        </button>
        <button
          type="button"
          class="chat-sidebar__tab"
          :class="{ 'chat-sidebar__tab--active': activeTab === 'archived' }"
          @click="setActiveTab('archived')"
        >
          已归档
        </button>
      </div>

      <!-- List -->
      <div class="flex-1 overflow-y-auto px-2">
        <NSpin :show="sessionsLoading" class="h-full">
          <TransitionGroup name="session-list" tag="div">
            <div
              v-if="filteredSessions.length === 0 && !sessionsLoading"
              class="flex flex-col items-center justify-center gap-3 py-16"
            >
              <icon-material-symbols:chat-outline-rounded class="text-36px text-[rgb(var(--base-text-color)/0.3)]" />
              <span class="text-muted text-13px">{{ activeTab === 'active' ? '暂无对话记录' : '暂无归档对话' }}</span>
            </div>

            <div
              v-for="session in filteredSessions"
              :key="session.conversationId"
              class="chat-session group mx-1 mb-0.5 flex cursor-pointer items-center gap-2.5"
              :class="{ 'chat-session--active': session.conversationId === conversationId }"
              @click="handleSelect(session.conversationId)"
            >
              <div
                class="chat-session__icon h-7 w-7 flex shrink-0 items-center justify-center rounded-8px text-15px"
                :class="{ 'chat-session__icon--active': session.conversationId === conversationId }"
              >
                <icon-material-symbols:chat-outline-rounded />
              </div>
              <div class="min-w-0 flex-1">
                <div class="chat-session__title truncate text-13px font-500">{{ session.title }}</div>
                <div class="chat-session__time text-12px">{{ formatDate(session.updatedAt) }}</div>
              </div>

              <!-- Action button -->
              <NPopconfirm v-if="activeTab === 'active'" @positive-click="handleArchive(session.conversationId)">
                <template #trigger>
                  <NButton
                    class="chat-session__action shrink-0 transition-opacity"
                    :class="{ 'opacity-0 group-hover:opacity-100': session.conversationId !== conversationId }"
                    text
                    size="tiny"
                    @click.stop
                  >
                    <template #icon>
                      <icon-material-symbols:archive-outline-rounded class="text-15px" />
                    </template>
                  </NButton>
                </template>
                归档后可在「已归档」中找回
              </NPopconfirm>
              <NButton
                v-else
                class="chat-session__action shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
                text
                size="tiny"
                @click.stop="handleUnarchive(session.conversationId)"
              >
                <template #icon>
                  <icon-material-symbols:unarchive-outline-rounded class="text-15px" />
                </template>
              </NButton>
            </div>
          </TransitionGroup>
        </NSpin>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* 会话侧栏：导航磨砂材质 + 发丝线分隔（DESIGN.md › Material Vocabulary） */
.chat-sidebar {
  background: var(--app-material-nav, var(--app-surface));
  backdrop-filter: var(--app-material-filter, saturate(180%) blur(20px));
  border-right: 1px solid var(--app-border);
  transition-duration: var(--app-dur-base, 250ms);
  transition-timing-function: var(--app-ease-standard, ease);
}
.chat-sidebar--collapsed {
  border-right-width: 0;
}

/* 分段控件：fill 轨道 + elevated 选中片，零阴影（DESIGN.md › 静态卡零阴影） */
.chat-sidebar__tabs {
  gap: 2px;
  padding: 2px;
  border-radius: 10px;
  background: var(--app-fill-subtle, var(--app-subtle));
}

.chat-sidebar__tab {
  flex: 1;
  padding: 5px 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  font-size: 12px;
  font-weight: 500;
  line-height: 16px;
  color: var(--app-muted);
  cursor: pointer;
  transition:
    background-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);

  &:hover {
    color: var(--app-text);
  }

  &:focus-visible {
    outline: 2px solid rgb(var(--primary-color) / 0.6);
    outline-offset: 1px;
  }
}

.chat-sidebar__tab--active {
  background: var(--app-elevated, var(--app-surface));
  color: var(--app-text);
}

/* 会话行：44px 行高、10px 圆角；选中 = fill-subtle 底 + 主文字色，靛蓝只给图标（DESIGN.md › Navigation） */
.chat-session {
  padding: 8px 12px;
  border-radius: 10px;
  transition: background-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);

  &:hover {
    background: var(--app-fill-hover, var(--app-subtle));
  }

  &:focus-visible {
    outline: 2px solid rgb(var(--primary-color) / 0.6);
    outline-offset: -2px;
  }
}

.chat-session--active {
  background: var(--app-fill-subtle, var(--app-subtle));
}

.chat-session__icon {
  background: transparent;
  color: var(--app-muted);
  transition:
    background-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);
}

.chat-session__icon--active {
  background: rgb(var(--primary-color) / 0.12);
  color: rgb(var(--primary-color));
}
.dark .chat-session__icon--active {
  color: rgb(var(--primary-400-color));
}

.chat-session__title {
  color: var(--app-text);
}

.chat-session__time {
  color: rgb(var(--base-text-color) / 0.45);
}

.chat-session__action {
  color: var(--app-muted);
}

.session-list-enter-active,
.session-list-leave-active {
  transition: all var(--app-dur-base, 250ms) var(--app-ease-standard, ease);
}
.session-list-enter-from,
.session-list-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}
</style>
