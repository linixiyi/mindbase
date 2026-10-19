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
const loadFailed = ref(false);

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
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'admin/conversation',
    params: appliedParams.value
  });
  if (!error) {
    list.value = data;
    loadFailed.value = false;
    scrollToBottom();
  } else {
    loadFailed.value = true;
  }
  loading.value = false;
}

const showEmpty = computed(() => !loading.value && list.value.length === 0);

const hasActiveFilters = computed(() => Object.keys(appliedParams.value).length > 0);
</script>

<template>
  <div class="workspace-page h-full flex-col overflow-hidden">
    <NCard
      title="聊天记录"
      :bordered="false"
      class="page-section min-h-0 flex-1"
      content-class="flex-col min-h-0 flex-1"
    >
      <NForm
        class="page-filters"
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
          <NDatePicker v-model:value="range" type="daterange" clearable />
        </NFormItem>
        <div class="filter-actions">
          <NButton type="primary" attr-type="submit" :loading="loading">查询</NButton>
          <NButton :disabled="loading" @click="resetFilters">重置</NButton>
        </div>
      </NForm>

      <NScrollbar ref="scrollbarRef" class="min-h-0 flex-1" content-class="h-full">
        <NSpin :show="loading" class="h-full" content-class="h-full">
          <div v-if="loadFailed" class="h-full flex flex-col items-center justify-center gap-3">
            <div class="text-muted text-12px">聊天记录加载失败，请稍后重试</div>
            <NButton size="small" :disabled="loading" @click="getList">重试</NButton>
          </div>
          <div v-else-if="showEmpty" class="h-full flex flex-col items-center justify-center gap-4">
            <div class="h-20 w-20 flex items-center justify-center rounded-xl bg-[rgb(var(--primary-color)/0.08)]">
              <icon-material-symbols:chat-outline-rounded class="text-36px text-[rgb(var(--primary-color)/0.5)]" />
            </div>
            <div class="text-body text-15px font-500">{{ hasActiveFilters ? '无匹配记录' : '暂无聊天记录' }}</div>
            <div class="text-muted text-12px">
              {{ hasActiveFilters ? '当前筛选条件下没有聊天记录，可重置筛选' : '开始对话后，聊天记录将显示在这里' }}
            </div>
            <NButton v-if="hasActiveFilters" size="small" :disabled="loading" @click="resetFilters">重置筛选</NButton>
          </div>
          <div v-else class="mx-auto max-w-[960px] w-full px-4">
            <VueMarkdownItProvider>
              <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" />
            </VueMarkdownItProvider>
          </div>
        </NSpin>
      </NScrollbar>
    </NCard>
  </div>
</template>
