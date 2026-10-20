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
    class="chat-sidebar relative flex flex-col shrink-0 overflow-hidden transition-[width,margin]"
    :class="collapsed ? 'chat-sidebar--collapsed w-0 min-w-0' : 'w-[260px] min-w-[260px]'"
  >
    <div class="w-[260px] flex flex-col flex-1 overflow-hidden" :class="{ 'pointer-events-none invisible': collapsed }">
      <div class="flex items-center justify-between px-5 pb-2 pt-4">
        <span class="text-16px font-600">对话列表</span>
        <div class="flex items-center gap-1">
          <NButton type="primary" size="small" secondary @click="handleNewChat">
            <template #icon>
              <icon-lucide:plus />
            </template>
            新对话
          </NButton>
          <NButton text size="tiny" @click="handleCollapse">
            <template #icon>
              <icon-lucide:panel-left-close />
            </template>
          </NButton>
        </div>
      </div>

      <!-- Tabs -->
      <div class="chat-sidebar__tabs mx-5 mb-2 flex">
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
      <div class="flex-1 overflow-y-auto px-3">
        <NSpin :show="sessionsLoading" class="h-full">
          <TransitionGroup name="session-list" tag="div">
            <div
              v-if="filteredSessions.length === 0 && !sessionsLoading"
              class="flex flex-col items-center justify-center gap-3 py-16"
            >
              <icon-lucide:message-circle class="text-36px text-[rgb(var(--base-text-color)/0.3)]" />
              <span class="text-muted text-14px">{{ activeTab === 'active' ? '暂无对话记录' : '暂无归档对话' }}</span>
            </div>

            <div
              v-for="session in filteredSessions"
              :key="session.conversationId"
              class="chat-session group mx-1 mb-1 flex cursor-pointer items-center gap-2.5"
              :class="{ 'chat-session--active': session.conversationId === conversationId }"
              @click="handleSelect(session.conversationId)"
            >
              <div
                class="chat-session__icon h-8 w-8 flex shrink-0 items-center justify-center rounded-10px text-16px"
                :class="{ 'chat-session__icon--active': session.conversationId === conversationId }"
              >
                <icon-lucide:message-circle />
              </div>
              <div class="min-w-0 flex-1">
                <div class="chat-session__title truncate text-16px font-500">{{ session.title }}</div>
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
                      <icon-lucide:archive class="text-15px" />
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
                  <icon-lucide:archive-restore class="text-15px" />
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
/* 会话侧栏 = 画布左缘的第二座浮岛：nav-material 磨砂 + 24px 圆角（xl）+ 浮岛档投影，
   与画布缘脱开 12px、岛内无边框——岛形由投影勾出，不发丝线（DESIGN.md › Layout › 聊台画布 /
   Navigation › 会话侧栏）。
   --app-shadow-island 由壳层分片落在 global.scss（亮/暗双值）；此处字面值仅作未落地时的兜底。 */
.chat-sidebar {
  margin: 12px 0 12px 12px;
  border-radius: var(--app-radius-xl, 24px);
  background: var(--app-material-nav, var(--app-surface));
  -webkit-backdrop-filter: var(--app-material-filter, saturate(180%) blur(20px));
  backdrop-filter: var(--app-material-filter, saturate(180%) blur(20px));
  box-shadow: var(--app-shadow-island, 0 1px 2px rgba(0, 0, 0, 0.04), 0 12px 32px rgba(0, 0, 0, 0.1));
  transition-duration: var(--app-dur-base, 250ms);
  transition-timing-function: var(--app-ease-standard, ease);
}
.chat-sidebar--collapsed {
  margin: 0;
  box-shadow: none;
}

/* ≤640px（appStore.isMobile = smaller('sm')）：浮岛收回为贴边抽屉 + scrim（岛形态只在桌面存在） */
@media (max-width: 639.98px) {
  .chat-sidebar {
    margin: 0;
    border-radius: 0;
    box-shadow: none;
  }
}

/* 分段控件：fill 轨道（md 14）+ elevated 选中片（sm 10，同心圆角），零阴影（DESIGN.md › Shapes） */
.chat-sidebar__tabs {
  gap: 2px;
  padding: 2px;
  border-radius: var(--app-radius-md, 14px);
  background: var(--app-fill-subtle, var(--app-subtle));
}

.chat-sidebar__tab {
  flex: 1;
  padding: 5px 12px;
  border: 0;
  border-radius: var(--app-radius-sm, 10px);
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

/* 会话行：10px 圆角；选中 = fill-subtle 底 + 主文字色，淡蓝只给图标（DESIGN.md › Navigation）。
   条目标题取 Title 16px（DESIGN.md › Typography › Hierarchy「会话列表条目标题」），
   字重按 nav-item 的 500 惯例——600 留给上方「对话列表」节头，保住岛内层级。 */
.chat-session {
  padding: 10px 12px;
  border-radius: var(--app-radius-sm, 10px);
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
