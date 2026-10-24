<script setup lang="tsx">
import type { DataTableColumns, PaginationProps, SelectOption } from 'naive-ui';
import { NButton, NInput, NPopconfirm, NRadio, NRadioGroup, NTag } from 'naive-ui';
import { fetchGetDocumentConflictList, fetchResolveDocumentConflict } from '@/service/api';

const appStore = useAppStore();

const statusOptions: SelectOption[] = [
  { label: '全部状态', value: 'all' },
  { label: '待处理', value: 'PENDING' },
  { label: '已保留新文档', value: 'KEEP_NEW' },
  { label: '已保留旧文档', value: 'KEEP_EXISTING' },
  { label: '非冲突', value: 'IGNORED' },
  { label: '已失效', value: 'OBSOLETE' }
];

const statusTagType: Record<Api.DocumentConflict.Status, 'warning' | 'success' | 'default' | 'error'> = {
  PENDING: 'warning',
  KEEP_NEW: 'success',
  KEEP_EXISTING: 'success',
  IGNORED: 'default',
  OBSOLETE: 'default'
};

const statusLabel: Record<Api.DocumentConflict.Status, string> = {
  PENDING: '待处理',
  KEEP_NEW: '已保留新文档',
  KEEP_EXISTING: '已保留旧文档',
  IGNORED: '非冲突',
  OBSOLETE: '已失效'
};

const actionOptions: Array<{ value: Api.DocumentConflict.Status; label: string; desc: string }> = [
  { value: 'KEEP_NEW', label: '保留新文档（删除旧文档）', desc: '以最近上传的版本为准' },
  { value: 'KEEP_EXISTING', label: '保留旧文档（删除新文档）', desc: '维持已有版本，撤回新上传' },
  { value: 'IGNORED', label: '不是冲突（仅标记）', desc: '两篇都保留，不删除任何文档' }
];

const loading = ref(false);
const data = ref<Api.DocumentConflict.Item[]>([]);

const filterStatus = ref<string>('all');
const appliedStatus = ref<string>('all');

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

const columns = computed<DataTableColumns<Api.DocumentConflict.Item>>(() => [
  {
    key: 'index',
    title: '序号',
    width: 64,
    render: (_, index) => (Number(pagination.page) - 1) * Number(pagination.pageSize) + index + 1
  },
  {
    key: 'status',
    title: '状态',
    width: 130,
    render: row => (
      <NTag bordered={false} type={statusTagType[row.status]}>
        {statusLabel[row.status]}
      </NTag>
    )
  },
  {
    key: 'newFile',
    title: '新文档',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: row => (
      <div class="min-w-0">
        <div class="truncate">{row.newFileName || '未知名文档'}</div>
        <div class="text-muted truncate text-xs">
          分块 #{row.newChunkId} · {row.newFileMd5}
        </div>
      </div>
    )
  },
  {
    key: 'existingFile',
    title: '已有文档',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: row => (
      <div class="min-w-0">
        <div class="truncate">{row.existingFileName || '未知名文档'}</div>
        <div class="text-muted truncate text-xs">
          分块 #{row.existingChunkId} · {row.existingFileMd5}
        </div>
      </div>
    )
  },
  {
    key: 'similarity',
    title: '相似度',
    width: 100,
    render: row => `${((row.similarity || 0) * 100).toFixed(1)}%`
  },
  {
    key: 'createdAt',
    title: '发现时间',
    width: 170,
    render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm')
  },
  {
    key: 'resolved',
    title: '处理人/时间',
    width: 170,
    render: row =>
      row.resolvedAt ? (
        `${row.resolvedBy || '-'} · ${dayjs(row.resolvedAt).format('MM-DD HH:mm')}`
      ) : (
        <span class="text-muted">-</span>
      )
  },
  {
    key: 'operate',
    title: '操作',
    width: 100,
    render: row =>
      row.status === 'PENDING' ? (
        <NButton type="primary" quaternary size="small" onClick={() => openResolveDialog(row)}>
          裁决
        </NButton>
      ) : (
        <NButton quaternary size="small" onClick={() => openDetailDialog(row)}>
          详情
        </NButton>
      )
  }
]);

async function getData() {
  loading.value = true;

  const { data: payload, error } = await fetchGetDocumentConflictList({
    status: appliedStatus.value === 'all' ? null : appliedStatus.value,
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

async function searchConflicts() {
  appliedStatus.value = filterStatus.value;
  pagination.page = 1;
  await getData();
}

async function resetFilters() {
  filterStatus.value = 'all';
  await searchConflicts();
}

const dialogVisible = ref(false);
const submitting = ref(false);
const resolvingRow = ref<Api.DocumentConflict.Item | null>(null);
const resolveAction = ref<Api.DocumentConflict.Status>('KEEP_NEW');
const resolveNote = ref('');
const readOnly = ref(false);

function openResolveDialog(row: Api.DocumentConflict.Item) {
  resolvingRow.value = row;
  resolveAction.value = 'KEEP_NEW';
  resolveNote.value = '';
  readOnly.value = false;
  dialogVisible.value = true;
}

function openDetailDialog(row: Api.DocumentConflict.Item) {
  resolvingRow.value = row;
  readOnly.value = true;
  dialogVisible.value = true;
}

function closeDialog() {
  dialogVisible.value = false;
  resolvingRow.value = null;
}

async function handleResolve() {
  const row = resolvingRow.value;
  if (!row) return;

  submitting.value = true;
  const { error } = await fetchResolveDocumentConflict(row.id, {
    action: resolveAction.value,
    note: resolveNote.value.trim() || undefined
  });

  if (!error) {
    window.$message?.success('冲突已处理');
    closeDialog();
    await getData();
  }

  submitting.value = false;
}

onMounted(() => {
  getData();
});
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch overflow-auto">
    <h1 class="page-title">冲突裁决</h1>
    <NCard
      title="疑似文档冲突"
      :bordered="false"
      size="small"
      class="page-section sm:flex-1-hidden"
      content-class="flex-col-stretch min-h-0 sm:h-full"
    >
      <template #header-extra>
        <NButton :loading="loading" @click="getData">刷新</NButton>
      </template>

      <NForm
        class="page-filters"
        label-placement="left"
        :show-feedback="false"
        inline
        @submit.prevent="searchConflicts"
      >
        <NFormItem label="状态">
          <NSelect v-model:value="filterStatus" :options="statusOptions" class="w-200px!" />
        </NFormItem>
        <div class="filter-actions">
          <NButton type="primary" attr-type="submit" :loading="loading">查询</NButton>
          <NButton :disabled="loading" @click="resetFilters">重置</NButton>
        </div>
      </NForm>

      <div class="min-h-0 sm:flex-1">
        <NDataTable
          :bordered="false"
          :columns="columns"
          :data="data"
          size="small"
          :flex-height="!appStore.isMobile"
          :scroll-x="1200"
          :loading="loading"
          remote
          :row-key="row => row.id"
          :pagination="mobilePagination"
          class="sm:h-full"
        >
          <template #empty>
            <div class="table-state">
              <div class="app-state-badge">
                <icon-lucide:copy class="text-icon-large" />
              </div>
              <template v-if="appliedStatus !== 'all'">
                <p class="table-state__title">没有符合筛选条件的冲突</p>
                <p class="table-state__desc">调整状态筛选，或清除筛选查看全部</p>
                <NButton size="small" :disabled="loading" @click="resetFilters">清除筛选</NButton>
              </template>
              <template v-else>
                <p class="table-state__title">暂无疑似冲突</p>
                <p class="table-state__desc">
                  文档向量化时检测到新旧版本关键值矛盾会记录到这里，由管理员决定保留哪一篇
                </p>
              </template>
            </div>
          </template>
        </NDataTable>
      </div>
    </NCard>

    <NModal
      v-model:show="dialogVisible"
      preset="dialog"
      :title="readOnly ? '冲突详情' : '裁决冲突'"
      :show-icon="false"
      :mask-closable="false"
      class="w-720px!"
    >
      <div v-if="resolvingRow" class="conflict-detail">
        <div class="snippet-grid">
          <div class="snippet">
            <div class="snippet__title">新文档 · {{ resolvingRow.newFileName || resolvingRow.newFileMd5 }}</div>
            <p class="snippet__text">{{ resolvingRow.newSnippet || '（无摘录）' }}</p>
            <p v-if="resolvingRow.newValues" class="snippet__values">新出现的关键值：{{ resolvingRow.newValues }}</p>
          </div>
          <div class="snippet">
            <div class="snippet__title">
              已有文档 · {{ resolvingRow.existingFileName || resolvingRow.existingFileMd5 }}
            </div>
            <p class="snippet__text">{{ resolvingRow.existingSnippet || '（无摘录）' }}</p>
            <p v-if="resolvingRow.existingValues" class="snippet__values">
              仅旧文档的关键值：{{ resolvingRow.existingValues }}
            </p>
          </div>
        </div>
        <p v-if="resolvingRow.llmReason" class="llm-reason">模型判断：{{ resolvingRow.llmReason }}</p>
        <p v-if="readOnly && resolvingRow.resolutionNote" class="llm-reason">
          处理备注：{{ resolvingRow.resolutionNote }}
        </p>

        <template v-if="!readOnly">
          <NRadioGroup v-model:value="resolveAction" class="action-group">
            <div class="flex flex-col gap-10px">
              <NRadio v-for="opt in actionOptions" :key="opt.value" :value="opt.value">
                <span>{{ opt.label }}</span>
                <span class="text-muted text-xs">（{{ opt.desc }}）</span>
              </NRadio>
            </div>
          </NRadioGroup>
          <NInput
            v-model:value="resolveNote"
            type="textarea"
            placeholder="处理备注（可选，最长 480 字）"
            maxlength="480"
            show-count
            class="mt-10px"
          />
          <NPopconfirm @positive-click="handleResolve">
            <template #trigger>
              <NButton type="primary" :loading="submitting" class="mt-10px">提交裁决</NButton>
            </template>
            裁决会按选择删除一篇文档（IGNORED 除外），操作不可撤销，确认提交吗？
          </NPopconfirm>
        </template>
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

.conflict-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.snippet-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;

  @media (max-width: 640px) {
    grid-template-columns: 1fr;
  }
}

.snippet {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border-radius: var(--app-radius-sm, 8px);
  background: var(--app-fill-subtle);
}

.snippet__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text);
  word-break: break-all;
}

.snippet__text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--app-text-secondary, var(--app-text));
  max-height: 160px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.snippet__values {
  margin: 0;
  font-size: 12px;
  color: var(--app-muted);
}

.llm-reason {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--app-muted);
}

.action-group {
  margin-top: 4px;
}
</style>
