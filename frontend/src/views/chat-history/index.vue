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

/** 管理端对话历史分页（pm-008）：page/size 以问答回合为单位，服务端分页 */
interface ConversationHistoryPage {
  records: Api.Chat.Message[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

const DEFAULT_PAGE_SIZE = 20;
const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

const pagination = reactive({
  page: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  itemCount: 0
});

function changePage(page: number) {
  pagination.page = page;
  getList();
}

function changePageSize(pageSize: number) {
  pagination.pageSize = pageSize;
  pagination.page = 1;
  getList();
}

async function searchHistory() {
  appliedParams.value = { ...params.value };
  pagination.page = 1;
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
  const { error, data } = await request<ConversationHistoryPage>({
    url: 'admin/conversation',
    params: { ...appliedParams.value, page: pagination.page, size: pagination.pageSize }
  });
  if (!error) {
    list.value = data.records;
    pagination.itemCount = data.totalElements;
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
    <h1 class="page-title">问答记录</h1>
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
      <div class="app-state-badge app-state-badge--error app-state-badge--page">
        <icon-lucide:circle-alert class="text-icon-xl" />
      </div>
      <p class="history-state__title">问答记录加载失败</p>
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
          <div class="app-state-badge app-state-badge--page">
            <icon-lucide:message-square class="text-icon-xl" />
          </div>
          <template v-if="hasActiveFilters">
            <p class="history-state__title">没有符合筛选条件的记录</p>
            <p class="history-state__desc">调整用户或时间范围，或清除筛选后再查询</p>
            <NButton size="small" :disabled="loading" @click="resetFilters">清除筛选</NButton>
          </template>
          <template v-else>
            <p class="history-state__title">暂无问答记录</p>
            <p class="history-state__desc">产生问答对话后，记录会汇总到这里</p>
          </template>
        </div>
      </NSpin>
    </NScrollbar>

    <!-- 服务端分页（pm-008）：仅渲染当前页，page/size 走查询参数 -->
    <div v-if="!errorMsg && pagination.itemCount > 0" class="history-pagination">
      <NPagination
        :page="pagination.page"
        :page-size="pagination.pageSize"
        :item-count="pagination.itemCount"
        :page-sizes="PAGE_SIZE_OPTIONS"
        show-size-picker
        :disabled="loading"
        @update:page="changePage"
        @update:page-size="changePageSize"
      />
    </div>
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

/* 分页收束页脚：与筛选行同律的发丝线，页码居中（服务端分页，pm-008） */
.history-pagination {
  display: flex;
  flex-shrink: 0;
  justify-content: center;
  padding-top: 12px;
  border-top: 1px solid var(--app-border);
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

/* R11 delight：页面级空态（两档律的 Display 档）改吃 global.scss 的 azure 徽标 tile——
   .app-state-badge--page = 欢迎徽标同几何（80/24/icon-xl），error 变体走语义红 wash；
   裸灰 ghost 图标就此从空态退役，__icon 规则随之删除 */
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
  line-height: 1.55;
  color: var(--app-muted);
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
