<script setup lang="tsx">
import type { DataTableColumns, PaginationProps } from 'naive-ui';
import { NButton, NPopconfirm, NTag } from 'naive-ui';
import { fetchGetDeadLetterList, fetchReplayDeadLetter } from '@/service/api';

const appStore = useAppStore();

const taskTypeLabel: Record<string, string> = {
  UPLOAD_PROCESS: '上传处理',
  REINDEX: '重建索引'
};

const loading = ref(false);
const data = ref<Api.DeadLetter.Item[]>([]);

const pagination = reactive<PaginationProps>({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 15, 20, 25, 30],
  onUpdatePage: async (page: number) => {
    pagination.page = page;
    await getData();
  },
  onUpdatePageSize: async (pageSize: number) => {
    pagination.pageSize = pageSize;
    pagination.page = 1;
    await getData();
  }
});

const mobilePagination = computed(() => ({ ...pagination, pageSlot: appStore.isMobile ? 3 : 9 }));

const submittingId = ref<number | null>(null);

function payloadInfo(row: Api.DeadLetter.Item): { fileName?: string; userId?: string } {
  if (!row.payload) return {};
  try {
    return JSON.parse(row.payload) as { fileName?: string; userId?: string };
  } catch {
    return {};
  }
}

const columns = computed<DataTableColumns<Api.DeadLetter.Item>>(() => [
  {
    key: 'index',
    title: '序号',
    width: 64,
    render: (_, index) => (Number(pagination.page) - 1) * Number(pagination.pageSize) + index + 1
  },
  {
    key: 'file',
    title: '文件',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: row => (
      <div class="min-w-0">
        <div class="truncate">{payloadInfo(row).fileName || '未知名文档'}</div>
        <div class="text-muted truncate text-xs">{row.fileMd5 || '载荷缺少 MD5'}</div>
      </div>
    )
  },
  {
    key: 'taskType',
    title: '任务类型',
    width: 100,
    render: row => (
      <NTag bordered={false} type={row.taskType === 'REINDEX' ? 'info' : 'default'}>
        {taskTypeLabel[row.taskType || ''] || row.taskType || '-'}
      </NTag>
    )
  },
  {
    key: 'errorMessage',
    title: '失败原因',
    minWidth: 240,
    ellipsis: { tooltip: true },
    render: row => row.errorMessage || <span class="text-muted">-</span>
  },
  {
    key: 'source',
    title: '死信位置',
    width: 200,
    render: row => `${row.sourceTopic}-${row.sourcePartition}@${row.sourceOffset}`
  },
  {
    key: 'createdAt',
    title: '入队时间',
    width: 170,
    render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm')
  },
  {
    key: 'operate',
    title: '操作',
    width: 130,
    render: row => (
      <div class="flex gap-4px">
        <NButton quaternary size="small" onClick={() => openDetailDialog(row)}>
          详情
        </NButton>
        <NPopconfirm onPositiveClick={() => handleReplay(row)}>
          {{
            default: () => '重放会把任务重新投递回文件处理链（可能再次计费），确认重放吗？',
            trigger: () => (
              <NButton type="primary" quaternary size="small" loading={submittingId.value === row.id}>
                重放
              </NButton>
            )
          }}
        </NPopconfirm>
      </div>
    )
  }
]);

async function getData() {
  loading.value = true;

  const { data: payload, error } = await fetchGetDeadLetterList({
    page: Number(pagination.page),
    size: Number(pagination.pageSize)
  });

  if (!error && payload) {
    data.value = payload.records || [];
    pagination.page = payload.current || Number(pagination.page);
    pagination.pageSize = payload.size || Number(pagination.pageSize);
    pagination.itemCount = payload.total || 0;
  }

  loading.value = false;
}

const dialogVisible = ref(false);
const detailRow = ref<Api.DeadLetter.Item | null>(null);

function openDetailDialog(row: Api.DeadLetter.Item) {
  detailRow.value = row;
  dialogVisible.value = true;
}

function closeDialog() {
  dialogVisible.value = false;
  detailRow.value = null;
}

async function handleReplay(row: Api.DeadLetter.Item) {
  submittingId.value = row.id;

  const { error } = await fetchReplayDeadLetter(row.id);

  if (!error) {
    window.$message?.success('已重新投递，任务回到文件处理链');
    // 重放成功后记录已从列表移除，弹窗里的详情随之失效，一并关闭
    if (detailRow.value?.id === row.id) closeDialog();
    await getData();
  }

  submittingId.value = null;
}

function prettyPayload(row: Api.DeadLetter.Item | null) {
  if (!row?.payload) return '（无载荷）';
  try {
    return JSON.stringify(JSON.parse(row.payload), null, 2);
  } catch {
    return row.payload;
  }
}

function formatTime(value: string) {
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss');
}

onMounted(() => {
  getData();
});
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch overflow-auto">
    <h1 class="page-title">死信队列</h1>
    <NCard
      title="文件处理死信"
      :bordered="false"
      size="small"
      class="page-section sm:flex-1-hidden"
      content-class="flex-col-stretch min-h-0 sm:h-full"
    >
      <template #header-extra>
        <NButton :loading="loading" @click="getData">刷新</NButton>
      </template>

      <div class="min-h-0 sm:flex-1">
        <NDataTable
          :bordered="false"
          :columns="columns"
          :data="data"
          size="small"
          :flex-height="!appStore.isMobile"
          :scroll-x="1100"
          :loading="loading"
          remote
          :row-key="row => row.id"
          :pagination="mobilePagination"
          class="sm:h-full"
        >
          <template #empty>
            <div class="table-state">
              <div class="app-state-badge">
                <icon-lucide:mail-warning class="text-icon-large" />
              </div>
              <p class="table-state__title">暂无死信任务</p>
              <p class="table-state__desc">文件处理任务重试耗尽会落到这里，由管理员排查后手动重放回处理链</p>
            </div>
          </template>
        </NDataTable>
      </div>
    </NCard>

    <NModal
      v-model:show="dialogVisible"
      preset="dialog"
      title="死信详情"
      :show-icon="false"
      :mask-closable="false"
      class="w-720px!"
    >
      <div v-if="detailRow" class="dead-letter-detail">
        <div class="meta-grid">
          <div class="meta">
            <span class="meta__label">文件</span>
            <span class="meta__value">{{ payloadInfo(detailRow).fileName || '未知名文档' }}</span>
          </div>
          <div class="meta">
            <span class="meta__label">MD5</span>
            <span class="meta__value">{{ detailRow.fileMd5 || '-' }}</span>
          </div>
          <div class="meta">
            <span class="meta__label">上传用户</span>
            <span class="meta__value">{{ payloadInfo(detailRow).userId || '-' }}</span>
          </div>
          <div class="meta">
            <span class="meta__label">死信位置</span>
            <span class="meta__value">
              {{ detailRow.sourceTopic }}-{{ detailRow.sourcePartition }}@{{ detailRow.sourceOffset }}
            </span>
          </div>
          <div class="meta">
            <span class="meta__label">入队时间</span>
            <span class="meta__value">{{ formatTime(detailRow.createdAt) }}</span>
          </div>
        </div>
        <div v-if="detailRow.errorMessage" class="error-block">
          <div class="error-block__title">失败原因</div>
          <p class="error-block__text">{{ detailRow.errorMessage }}</p>
        </div>
        <div class="payload-block">
          <div class="payload-block__title">任务载荷</div>
          <pre class="payload-block__text">{{ prettyPayload(detailRow) }}</pre>
        </div>

        <NPopconfirm @positive-click="handleReplay(detailRow)">
          <template #trigger>
            <NButton type="primary" :loading="submittingId === detailRow.id" class="mt-10px">重放任务</NButton>
          </template>
          重放会把任务重新投递回文件处理链（可能再次计费），确认重放吗？
        </NPopconfirm>
      </div>
      <template #action>
        <NButton @click="closeDialog">关闭</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.table-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 24px;
  text-align: center;
  animation: table-state-in var(--app-dur-base) var(--app-ease-standard);
}

.table-state__title {
  margin: 8px 0 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--app-text);
}

.table-state__desc {
  margin: 0;
  max-width: 360px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--app-muted);
}

@keyframes table-state-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

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

.dead-letter-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;

  @media (max-width: 640px) {
    grid-template-columns: 1fr;
  }
}

.meta {
  display: flex;
  gap: 8px;
  min-width: 0;
  font-size: 13px;
  line-height: 1.6;
}

.meta__label {
  flex-shrink: 0;
  color: var(--app-muted);
}

.meta__value {
  color: var(--app-text);
  word-break: break-all;
}

.error-block,
.payload-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border-radius: var(--app-radius-sm, 8px);
  background: var(--app-fill-subtle);
}

.error-block__title,
.payload-block__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text);
}

.error-block__text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--app-text-secondary, var(--app-text));
  white-space: pre-wrap;
  word-break: break-all;
}

.payload-block__text {
  margin: 0;
  max-height: 240px;
  overflow-y: auto;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-secondary, var(--app-text));
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
