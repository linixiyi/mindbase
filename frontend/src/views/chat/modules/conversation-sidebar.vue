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

// 会话重命名（pm-016）：行内编辑，Enter/失焦提交、Esc 取消；提交后 store 即时更新标题并持久化
const editingId = ref('');
const editingTitle = ref('');

function startRename(session: Api.Chat.ConversationSession) {
  editingId.value = session.conversationId;
  editingTitle.value = session.title;
}

function cancelRename() {
  editingId.value = '';
  editingTitle.value = '';
}

async function commitRename(session: Api.Chat.ConversationSession) {
  if (editingId.value !== session.conversationId) {
    return;
  }
  const title = editingTitle.value.trim();
  cancelRename();
  if (!title || title === session.title) {
    return;
  }
  await chatStore.renameSession(session.conversationId, title);
}

function handleDelete(cid: string) {
  chatStore.deleteSession(cid);
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
    class="app-glass-rim chat-sidebar relative flex flex-col shrink-0 overflow-hidden transition-[width,margin]"
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
              <icon-lucide:message-circle class="chat-sidebar__empty-icon text-icon-xl" />
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
                class="chat-session__icon h-8 w-8 flex shrink-0 items-center justify-center rounded-10px text-icon-small"
                :class="{ 'chat-session__icon--active': session.conversationId === conversationId }"
              >
                <icon-lucide:message-circle />
              </div>
              <div class="min-w-0 flex-1">
                <NInput
                  v-if="editingId === session.conversationId"
                  v-model:value="editingTitle"
                  class="chat-session__rename"
                  size="tiny"
                  :maxlength="50"
                  :autofocus="true"
                  @click.stop
                  @keyup.enter="commitRename(session)"
                  @keyup.esc="cancelRename"
                  @blur="commitRename(session)"
                />
                <template v-else>
                  <div class="chat-session__title truncate text-16px font-500">{{ session.title }}</div>
                  <div class="chat-session__time text-12px">{{ formatDate(session.updatedAt) }}</div>
                </template>
              </div>

              <!-- Action buttons（pm-016：重命名 + 删除，随归档/恢复同区收纳） -->
              <div
                v-if="editingId !== session.conversationId"
                class="flex shrink-0 items-center gap-0.5 transition-opacity"
                :class="{ 'opacity-0 group-hover:opacity-100': session.conversationId !== conversationId }"
                @click.stop
              >
                <NButton
                  v-if="activeTab === 'active'"
                  class="chat-session__action"
                  text
                  size="tiny"
                  title="重命名"
                  @click="startRename(session)"
                >
                  <template #icon>
                    <icon-lucide:pencil class="text-icon-small" />
                  </template>
                </NButton>
                <NPopconfirm v-if="activeTab === 'active'" @positive-click="handleArchive(session.conversationId)">
                  <template #trigger>
                    <NButton class="chat-session__action" text size="tiny" title="归档">
                      <template #icon>
                        <icon-lucide:archive class="text-icon-small" />
                      </template>
                    </NButton>
                  </template>
                  归档后可在「已归档」中找回
                </NPopconfirm>
                <NButton
                  v-else
                  class="chat-session__action"
                  text
                  size="tiny"
                  title="恢复"
                  @click="handleUnarchive(session.conversationId)"
                >
                  <template #icon>
                    <icon-lucide:archive-restore class="text-icon-small" />
                  </template>
                </NButton>
                <NPopconfirm @positive-click="handleDelete(session.conversationId)">
                  <template #trigger>
                    <NButton class="chat-session__action" text size="tiny" title="删除">
                      <template #icon>
                        <icon-lucide:trash-2 class="text-icon-small" />
                      </template>
                    </NButton>
                  </template>
                  删除后该会话及其消息将无法恢复
                </NPopconfirm>
              </div>
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
   Navigation › 会话侧栏）。R12 overdrive：折射走三档梯的 ISLAND 档；边缘高光由共享的
   各向异性 rim 环（.app-glass-rim::before，DESIGN.md §18）给出——顶缘最亮、左右收暗、
   底缘微光，与主浮岛、顶栏、命令面板同族。材质与投影 token 均由 global.scss 单源供给
   （亮/暗双值），此处不再留字面兜底。 */
.chat-sidebar {
  margin: 12px 0 12px 12px;
  border-radius: var(--app-radius-xl, 24px);
  background: var(--app-material-nav, var(--app-surface));
  -webkit-backdrop-filter: var(--app-glass-filter-island);
  backdrop-filter: var(--app-glass-filter-island);
  box-shadow: var(--app-shadow-island);
  transition-duration: var(--app-dur-base, 250ms);
  transition-timing-function: var(--app-ease-standard, ease);
}
.chat-sidebar--collapsed {
  margin: 0;
  box-shadow: none;

  /* 收起是贴墙的零宽形态——rim 环随投影一起退场 */
  &::before {
    content: none;
  }
}

/* ≤640px（appStore.isMobile = smaller('sm')）：浮岛收回为贴边抽屉 + scrim（岛形态只在桌面存在）；
   抽屉平贴（§12「Mobile full-sheet stays flat」），rim 环不出现 */
@media (max-width: 639.98px) {
  .chat-sidebar {
    margin: 0;
    border-radius: 0;
    box-shadow: none;

    &::before {
      content: none;
    }
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
    color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    transform var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);

  &:hover {
    color: var(--app-text);
  }

  /* R10 animate：分段控件是原生 button，吃不到全局 .n-button 按压律——就地补上同一记
     scale 0.97（DESIGN.md › Motion › 微反馈），与 NButton 家族同一手感 */
  &:active {
    transform: scale(0.97);
  }

  &:focus-visible {
    outline: 2px solid rgb(var(--primary-color) / 0.6);
    outline-offset: 1px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .chat-sidebar__tab:active {
    transform: none;
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

/* 行内重命名（pm-016）：NInput 就地替换标题行，尺寸贴行高，材质吃全局 NInput 单源不另立词汇 */
.chat-session__rename {
  font-size: 14px;
}

.chat-session__time {
  color: var(--app-text-tertiary);
}

/* 空态图标吃全站唯一的 ghost 档（--app-icon-ghost 0.24，R9 quieter 归一：原 0.3 档就此退役） */
.chat-sidebar__empty-icon {
  color: var(--app-icon-ghost);
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
