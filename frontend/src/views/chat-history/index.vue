<script setup lang="ts">
import type { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from '../chat/modules/chat-message.vue';

defineOptions({
  name: 'ChatHistory'
});

const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

const list = ref<Api.Chat.Message[]>([]);
const loading = ref(false);
const errorMsg = ref('');

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
const userId = ref<number | null>(null);

const params = computed(() => {
  const query: {
    userid?: number;
    start_date?: string;
    end_date?: string;
  } = {};

  if (userId.value !== null) {
    query.userid = userId.value;
  }

  if (range.value) {
    query.start_date = dayjs(range.value[0]).format('YYYY-MM-DD');
    query.end_date = dayjs(range.value[1]).format('YYYY-MM-DD');
  }

  return query;
});

const appliedParams = ref<{ userid?: number; start_date?: string; end_date?: string }>({});

const hasActiveFilters = computed(() => Boolean(appliedParams.value.userid || appliedParams.value.start_date));

async function searchHistory() {
  appliedParams.value = { ...params.value };
  await getList();
}

async function resetFilters() {
  userId.value = null;
  range.value = null;
  await searchHistory();
}

onMounted(getList);

async function getList() {
  loading.value = true;
  errorMsg.value = '';
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'admin/conversation',
    params: appliedParams.value
  });
  if (!error) {
    list.value = data;
    scrollToBottom();
  } else {
    errorMsg.value = '无法连接到记录服务，请检查网络后重试';
  }
  loading.value = false;
}
</script>

<template>
  <!-- 去套盒：无卡片——页题与筛选行直接落在地面上，消息流不装盒（DESIGN.md › Layout › 去套盒/禁令①） -->
  <div class="workspace-page h-full flex-col overflow-hidden">
    <h1 class="page-title">聊天记录</h1>
    <NForm
      class="page-filters history-filters mb-0!"
      :model="params"
      label-placement="left"
      :show-feedback="false"
      inline
      @submit.prevent="searchHistory"
    >
      <NFormItem label="用户">
        <TheSelect
          v-model:value="userId"
          url="admin/users/list"
          :params="{ page: 1, size: 999 }"
          key-field="content"
          value-field="userId"
          label-field="username"
          class="w-200px!"
          placeholder="全部用户"
        />
      </NFormItem>
      <NFormItem label="时间">
        <NDatePicker v-model:value="range" type="daterange" clearable class="w-260px!" />
      </NFormItem>
      <div class="filter-actions">
        <NButton type="primary" attr-type="submit" :loading="loading">查询</NButton>
        <NButton :disabled="loading" @click="resetFilters">重置</NButton>
      </div>
    </NForm>

    <div v-if="errorMsg" class="history-state history-state--error min-h-0 flex-1">
      <icon-lucide:circle-alert class="history-state__icon" />
      <p class="history-state__title">聊天记录加载失败</p>
      <p class="history-state__desc">{{ errorMsg }}</p>
      <NButton size="small" :disabled="loading" @click="getList">
        <template #icon>
          <icon-lucide:rotate-cw />
        </template>
        重试
      </NButton>
    </div>
    <NScrollbar v-else ref="scrollbarRef" class="min-h-0 flex-1">
      <NSpin :show="loading" class="h-full">
        <div class="history-messages">
          <VueMarkdownItProvider>
            <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" />
          </VueMarkdownItProvider>
        </div>
        <div v-if="!list.length" class="history-state">
          <icon-lucide:message-square class="history-state__icon" />
          <template v-if="hasActiveFilters">
            <p class="history-state__title">没有符合筛选条件的记录</p>
            <p class="history-state__desc">调整用户或时间范围，或清除筛选后再查询</p>
            <NButton size="small" :disabled="loading" @click="resetFilters">清除筛选</NButton>
          </template>
          <template v-else>
            <p class="history-state__title">暂无聊天记录</p>
            <p class="history-state__desc">产生问答对话后，记录会汇总到这里</p>
          </template>
        </div>
      </NSpin>
    </NScrollbar>
  </div>
</template>

<style scoped lang="scss">
/* 页题：Display 32/600/1.2（DESIGN.md › Typography）。上方 24px 与 GlobalContent 的 24px 顶距
   叠成 4xl 48；下方间距交给 .workspace-page 的区块 gap——标题上方留白 ≥ 下方 ×1.5 */
.page-title {
  flex-shrink: 0;
  margin: 24px 0 0;
  font-size: 32px;
  font-weight: 600;
  line-height: 1.2;
  color: rgb(var(--base-text-color));
}

/* 去套盒后筛选行直接落地：发丝线收束固定表头区，间距交给 workspace-page gap */
.history-filters {
  flex-shrink: 0;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--app-border);
}

.history-messages {
  max-width: 800px;
  margin: 0 auto;
  padding: 24px 0 48px;
}

.history-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 320px;
  padding: 48px 24px;
  text-align: center;
  animation: history-state-in var(--app-dur-base) var(--app-ease-standard);
}

.history-state__icon {
  font-size: 48px;
  color: rgb(var(--base-text-color) / 0.24);
}

.history-state__title {
  margin: 8px 0 0;
  font-size: 32px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--app-text);
}

.history-state__desc {
  margin: 0;
  max-width: 400px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--app-muted);
}

.history-state--error .history-state__icon {
  color: rgb(var(--error-700-color));
}

html.dark .history-state--error .history-state__icon {
  color: rgb(var(--error-400-color));
}

@keyframes history-state-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
