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
      <!-- 次级工具行：日期筛选收敛到阅读列右端，不与对话内容抢注意力 -->

      <div v-if="!showEmpty" class="chat-date-filter">
        <NForm :model="params" label-placement="left" :show-feedback="false" inline>
          <NFormItem label="时间" size="small">
            <NDatePicker v-model:value="range" type="daterange" clearable size="small" />
          </NFormItem>
        </NForm>
      </div>

      <!-- Empty state -->
      <div v-if="showEmpty" class="flex flex-col flex-1 items-center justify-center gap-4">
        <div class="h-20 w-20 flex items-center justify-center rounded-24px bg-[var(--app-subtle)]">
          <icon-material-symbols:chat-outline-rounded class="text-36px text-[rgb(var(--base-text-color)/0.4)]" />
        </div>
        <div class="text-body text-15px font-600">
          {{ conversationId ? '开始新对话' : '选择或创建一个对话' }}
        </div>
        <div class="text-muted text-13px">在左侧选择一个对话，或点击「新对话」开始</div>
      </div>

      <!-- Message list -->
      <NScrollbar v-else ref="scrollbarRef" class="flex-1">
        <NSpin :show="loading">
          <div class="mx-auto max-w-[800px] w-full px-4 pb-4">
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
/* 日期筛选：与消息列同轨（800px 右对齐）、压缩到工具行高度；
   选择器静置透明无框，悬停才现发丝线——次级视觉，随 Naive 焦点环保持一线靛。 */
.chat-date-filter {
  display: flex;
  justify-content: flex-end;
  width: 100%;
  max-width: 800px;
  margin: 0 auto;
  padding: 6px 16px 4px;

  :deep(.n-form-item-label) {
    color: var(--app-text-tertiary);
    font-size: 12px;
  }

  :deep(.n-input) {
    background-color: transparent;
    border-color: transparent;
    transition:
      border-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
      background-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);
  }

  :deep(.n-input:hover) {
    border-color: var(--app-border);
  }

  :deep(.n-input.n-input--focus) {
    border-color: rgb(var(--primary-color) / 0.5);
  }
}

@media (max-width: 640px) {
  .chat-date-filter {
    padding-left: 56px;
  }
  .chat-date-filter :deep(.n-form-item-label) {
    display: none;
  }
  .chat-date-filter :deep(.n-form-item) {
    margin-right: 0;
    min-width: 0;
  }
  .chat-date-filter :deep(.n-date-picker) {
    min-width: 0;
    width: 100%;
  }
}
</style>
