<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { list, sessionId, conversationId } = storeToRefs(chatStore);

const loading = ref(false);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number] | null>(null);

function getRetrievalQueryFallback(index: number) {
  for (let i = index - 1; i >= 0; i -= 1) {
    const candidate = list.value[i];
    if (candidate?.role === 'user') {
      return candidate.content || '';
    }
  }
  return '';
}

const params = computed(() => {
  const p: Record<string, string> = {};
  if (range.value) {
    p.start_date = dayjs(range.value[0]).format('YYYY-MM-DD');
    p.end_date = dayjs(range.value[1]).format('YYYY-MM-DD');
  }
  if (conversationId.value) {
    p.conversationId = conversationId.value;
  }
  return p;
});

let historyRequestId = 0;
watch(params, getList, { immediate: true });

async function getList() {
  historyRequestId += 1;
  const requestId = historyRequestId;
  const messagesBeforeRequest = [...list.value];
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: params.value
  });
  if (requestId !== historyRequestId) return;
  const messagesUnchanged =
    list.value.length === messagesBeforeRequest.length &&
    list.value.every((message, index) => message === messagesBeforeRequest[index]);
  const hasPending = list.value.some(message => ['pending', 'loading'].includes(message.status || ''));
  if (!error && messagesUnchanged && !hasPending) {
    list.value = data;
  }
  loading.value = false;
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});

const showEmpty = computed(() => !loading.value && list.value.length === 0);
</script>

<template>
  <Suspense>
    <div class="h-0 flex flex-col flex-1">
      <!-- 日期筛选：默认隐藏，收进右上角图标触发的浮层（overlay 材质由 global.scss 的 .n-popover 承担）；range → params 数据流零改动 -->
      <NPopover v-if="!showEmpty" trigger="click" placement="bottom-end" :show-arrow="false">
        <template #trigger>
          <button
            class="chat-filter-toggle absolute right-3 top-3 z-20 h-9 w-9 inline-flex items-center justify-center rounded-10px"
            :class="{ 'chat-filter-toggle--active': Array.isArray(range) }"
            aria-label="按日期筛选消息"
            title="按日期筛选消息"
          >
            <icon-lucide:calendar class="text-18px" />
          </button>
        </template>
        <div class="chat-filter-panel">
          <div class="chat-filter-panel__label">按日期筛选历史消息</div>
          <NDatePicker v-model:value="range" type="daterange" clearable size="small" />
        </div>
      </NPopover>

      <!-- Empty state -->
      <div v-if="showEmpty" class="flex flex-col flex-1 items-center justify-center gap-4">
        <div class="h-20 w-20 flex items-center justify-center rounded-24px bg-[var(--app-subtle)]">
          <icon-lucide:message-circle class="text-36px text-[rgb(var(--base-text-color)/0.4)]" />
        </div>
        <div class="text-body text-20px font-600">
          {{ conversationId ? '开始新对话' : '选择或创建一个对话' }}
        </div>
        <div class="text-muted text-14px">在左侧选择一个对话，或点击「新对话」开始</div>
      </div>

      <!-- Message list -->
      <NScrollbar v-else ref="scrollbarRef" class="flex-1">
        <NSpin :show="loading">
          <div class="mx-auto max-w-[800px] w-full px-4 pb-6 pt-12">
            <VueMarkdownItProvider>
              <ChatMessage
                v-for="(item, index) in list"
                :key="index"
                :msg="item"
                :session-id="sessionId"
                :retrieval-query-fallback="getRetrievalQueryFallback(index)"
              />
            </VueMarkdownItProvider>
          </div>
        </NSpin>
      </NScrollbar>
    </div>
  </Suspense>
</template>

<style scoped lang="scss">
/* 筛选入口：与左侧 conversation-toggle 同款的悬浮小钮（nav-material + e1），
   有筛选值时一线蓝标示激活。absolute 相对 chat/index.vue 的画布列定位。 */
.chat-filter-toggle {
  background: var(--app-material-nav, var(--app-surface));
  -webkit-backdrop-filter: var(--app-material-filter, saturate(180%) blur(20px));
  backdrop-filter: var(--app-material-filter, saturate(180%) blur(20px));
  color: var(--app-muted);
  border: 1px solid var(--app-border);
  box-shadow: var(--app-shadow-e1);
  cursor: pointer;
  transition:
    color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    background-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);
}
.chat-filter-toggle:hover {
  color: rgb(var(--primary-color));
  background: var(--app-fill-hover, var(--app-subtle));
}
.chat-filter-toggle--active,
.chat-filter-toggle--active:hover {
  color: rgb(var(--primary-color));
}
.dark .chat-filter-toggle--active,
.dark .chat-filter-toggle--active:hover {
  color: rgb(var(--primary-400-color));
}
.chat-filter-toggle:focus-visible {
  outline: 2px solid rgb(var(--primary-color) / 0.6);
  outline-offset: 2px;
}

/* 弹层内文：Footnote 说明 + 控件层日期选择器 */
.chat-filter-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.chat-filter-panel__label {
  font-size: 12px;
  color: var(--app-text-tertiary);
}
</style>
