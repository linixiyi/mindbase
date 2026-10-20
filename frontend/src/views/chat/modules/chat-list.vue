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

// R2 welcome：建议问法来自本会话真实回答过的产品能力（知识库问答/引用溯源/混合检索/总结）
const suggestions = [
  'MindBase 的引用标注如何做到可溯源？',
  '什么是混合检索？',
  '帮我总结知识库里的内容'
];

function applySuggestion(q: string) {
  chatStore.input = { message: q, conversationId: chatStore.conversationId || undefined };
}

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

      <!-- Empty state → welcome（R2 animate+delight）：产品语言直接引导提问，建议问法一键填入输入框；
           入场为一次性 stagger（250ms emphasized），reduced-motion 下被全局压扁 -->
      <div v-if="showEmpty" class="chat-welcome">
        <div class="chat-welcome__badge">
          <icon-lucide:message-circle class="text-36px text-[rgb(var(--primary-color))]" />
        </div>
        <div class="chat-welcome__title">
          {{ conversationId ? '开始新对话' : '选择或创建一个对话' }}
        </div>
        <div class="chat-welcome__desc">直接在下方提问——回答基于知识库检索生成，句末附引用标注</div>
        <div class="chat-welcome__suggest">
          <span class="chat-welcome__label">试试这样问</span>
          <button
            v-for="(q, i) in suggestions"
            :key="q"
            type="button"
            class="chat-welcome__chip"
            :style="{ animationDelay: `${140 + i * 60}ms` }"
            @click="applySuggestion(q)"
          >
            {{ q }}
          </button>
        </div>
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

/* R2 welcome：一次性 stagger 入场（250ms emphasized）；建议问法为胶囊，hover 提到主色 */
.chat-welcome {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  text-align: center;
}

.chat-welcome__badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 80px;
  border-radius: 24px;
  background: rgb(var(--primary-color) / 0.08);
  animation: chat-welcome-rise var(--app-dur-base, 250ms) var(--app-ease-emphasized, ease) both;
}

.chat-welcome__title {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  color: rgb(var(--base-text-color));
  animation: chat-welcome-rise var(--app-dur-base, 250ms) var(--app-ease-emphasized, ease) 60ms both;
}

.chat-welcome__desc {
  color: var(--app-text-secondary);
  font-size: 14px;
  line-height: 1.55;
  animation: chat-welcome-rise var(--app-dur-base, 250ms) var(--app-ease-emphasized, ease) 120ms both;
}

.chat-welcome__suggest {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 8px;
  max-width: 560px;
  animation: chat-welcome-rise var(--app-dur-base, 250ms) var(--app-ease-emphasized, ease) 180ms both;
}

.chat-welcome__label {
  width: 100%;
  font-size: 12px;
  color: var(--app-text-tertiary);
}

.chat-welcome__chip {
  padding: 6px 14px;
  border: 1px solid var(--app-border);
  border-radius: 9999px;
  background: var(--app-elevated, var(--app-surface));
  font-size: 13px;
  color: var(--app-text-secondary);
  cursor: pointer;
  transition:
    background-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    border-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);
}

.chat-welcome__chip:hover {
  border-color: rgb(var(--primary-color) / 0.4);
  background: rgb(var(--primary-color) / 0.08);
  color: rgb(var(--primary-color));
}

@keyframes chat-welcome-rise {
  from {
    opacity: 0;
    transform: translateY(10px);
  }

  to {
    opacity: 1;
    transform: none;
  }
}
</style>
