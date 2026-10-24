<script setup lang="tsx">
import type { DataTableColumns, SelectOption } from 'naive-ui';
import { NButton, NPopconfirm, NTag } from 'naive-ui';
import { request } from '@/service/request';
import {
  fetchGetKnowledgeIndexOverview,
  fetchRollbackKnowledgeIndex,
  fetchStartKnowledgeIndexRebuild
} from '@/service/api';

const appStore = useAppStore();

const loading = ref(false);
const overview = ref<Api.KnowledgeIndex.Overview | null>(null);
const rebuild = ref<Api.KnowledgeIndex.RebuildStatus | null>(null);
const rollbackSubmitting = ref('');

const rebuildStateTag: Record<Api.KnowledgeIndex.RebuildState, 'info' | 'success' | 'error'> = {
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'error'
};

const rebuildStateLabel: Record<Api.KnowledgeIndex.RebuildState, string> = {
  RUNNING: '重建中',
  SUCCEEDED: '已完成',
  FAILED: '失败'
};

const rebuildPercentage = computed(() => {
  if (!rebuild.value || rebuild.value.totalFiles <= 0) return 0;
  return Math.round((rebuild.value.processedFiles / rebuild.value.totalFiles) * 100);
});

const columns = computed<DataTableColumns<Api.KnowledgeIndex.IndexInfo>>(() => [
  {
    key: 'name',
    title: '索引',
    minWidth: 260,
    ellipsis: { tooltip: true },
    render: row => (
      <div class="flex items-center gap-2">
        <span class="truncate text-3 font-mono">{row.name}</span>
        {row.live ? (
          <NTag bordered={false} type="success">
            线上
          </NTag>
        ) : null}
      </div>
    )
  },
  {
    key: 'docCount',
    title: '文档数',
    width: 100,
    render: row => Number(row.docCount || 0).toLocaleString()
  },
  {
    key: 'provider',
    title: 'Embedding 供应商',
    width: 160,
    render: row => row.meta?.embeddingProvider || '-'
  },
  {
    key: 'modelVersion',
    title: '模型版本',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: row => row.meta?.modelVersion || '-'
  },
  {
    key: 'createdAt',
    title: '创建时间',
    width: 170,
    render: row => (row.meta?.createdAt ? dayjs(row.meta.createdAt).format('YYYY-MM-DD HH:mm') : '-')
  },
  {
    key: 'operate',
    title: '操作',
    width: 110,
    render: row =>
      row.live ? (
        <span class="text-muted">当前生效</span>
      ) : (
        <NPopconfirm onPositiveClick={() => handleRollback(row.name)}>
          {{
            default: () => `回滚后线上检索立即切换到「${row.name}」，确认继续吗？`,
            trigger: () => (
              <NButton type="warning" quaternary size="small" loading={rollbackSubmitting.value === row.name}>
                回滚
              </NButton>
            )
          }}
        </NPopconfirm>
      )
  }
]);

async function getOverview() {
  loading.value = true;

  const { data: payload, error } = await fetchGetKnowledgeIndexOverview();

  if (!error && payload) {
    overview.value = payload;
    rebuild.value = payload.rebuild || null;
  }

  loading.value = false;
}

async function pollRebuildStatus() {
  const { data: payload, error } = await request<Api.KnowledgeIndex.RebuildStatus | null>({
    url: '/admin/knowledge-index/rebuild'
  });
  if (error) return;

  const prevState = rebuild.value?.state;
  rebuild.value = payload || null;

  if (payload && prevState === 'RUNNING' && payload.state !== 'RUNNING') {
    if (payload.state === 'SUCCEEDED') {
      window.$message?.success('embedding 重建已完成，别名已切换');
    } else {
      window.$message?.error(`重建失败：${payload.message || '未知原因'}`);
    }
    await getOverview();
  }
}

let rebuildTimer: ReturnType<typeof setInterval> | null = null;

function syncRebuildTimer() {
  const running = rebuild.value?.state === 'RUNNING';
  if (running && rebuildTimer === null) {
    rebuildTimer = setInterval(pollRebuildStatus, 5000);
  } else if (!running && rebuildTimer !== null) {
    clearInterval(rebuildTimer);
    rebuildTimer = null;
  }
}

watch(rebuild, syncRebuildTimer, { immediate: true });

onUnmounted(() => {
  if (rebuildTimer !== null) {
    clearInterval(rebuildTimer);
    rebuildTimer = null;
  }
});

const rebuildVisible = ref(false);
const rebuildSubmitting = ref(false);
const rebuildProvider = ref<string | null>(null);
const rebuildFresh = ref(false);
const providerOptions = ref<SelectOption[]>([]);
const providerLoading = ref(false);

async function loadProviderOptions() {
  providerLoading.value = true;

  const { data: payload, error } = await request<Api.Admin.ModelProviderSettings>({ url: '/admin/model-providers' });

  if (!error && payload?.embedding?.providers) {
    providerOptions.value = payload.embedding.providers
      .filter(item => item.enabled && (item.dimension || 0) > 0)
      .map(item => ({
        label: `${item.displayName || item.provider}（${item.model || '未配置模型'}）`,
        value: item.provider
      }));
  }

  providerLoading.value = false;
}

function openRebuildDialog() {
  rebuildProvider.value = null;
  rebuildFresh.value = false;
  rebuildVisible.value = true;
  if (providerOptions.value.length === 0) {
    loadProviderOptions();
  }
}

async function handleStartRebuild() {
  if (!rebuildProvider.value) {
    window.$message?.warning('请选择目标 embedding 供应商');
    return;
  }

  rebuildSubmitting.value = true;
  const { error } = await fetchStartKnowledgeIndexRebuild({
    targetProvider: rebuildProvider.value,
    fresh: rebuildFresh.value
  });

  if (!error) {
    window.$message?.success('重建任务已提交，进度见上方状态区');
    rebuildVisible.value = false;
    await getOverview();
  }

  rebuildSubmitting.value = false;
}

async function handleRollback(index: string) {
  rollbackSubmitting.value = index;

  const { error } = await fetchRollbackKnowledgeIndex(index);

  if (!error) {
    window.$message?.success(`已回滚到索引 ${index}`);
    await getOverview();
  }

  rollbackSubmitting.value = '';
}

onMounted(() => {
  getOverview();
});
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch overflow-auto">
    <h1 class="page-title">索引管理</h1>

    <NCard title="线上状态" :bordered="false" size="small" class="page-section">
      <NSpin :show="loading">
        <div v-if="overview" class="overview-grid">
          <div class="overview-item">
            <p class="overview-item__label">线上索引</p>
            <p class="overview-item__value font-mono">{{ overview.liveIndex || '未就绪' }}</p>
          </div>
          <div class="overview-item">
            <p class="overview-item__label">旧版具体索引</p>
            <p class="overview-item__value">
              <NTag :bordered="false" :type="overview.legacyConcreteIndex ? 'warning' : 'default'">
                {{ overview.legacyConcreteIndex ? '存在（建议迁移）' : '无' }}
              </NTag>
            </p>
          </div>
          <div class="overview-item overview-item--wide">
            <p class="overview-item__label">重建任务</p>
            <template v-if="rebuild">
              <div class="rebuild-summary">
                <NTag :bordered="false" :type="rebuildStateTag[rebuild.state]">
                  {{ rebuildStateLabel[rebuild.state] }}
                </NTag>
                <span class="text-sm">{{ rebuild.phase }}</span>
                <span v-if="rebuild.state === 'RUNNING'" class="text-muted text-sm">
                  {{ rebuild.processedFiles }}/{{ rebuild.totalFiles }} 个文件
                </span>
              </div>
              <NProgress
                v-if="rebuild.state === 'RUNNING'"
                type="line"
                :percentage="rebuildPercentage"
                :height="8"
                class="mt-6px"
              />
              <p v-if="rebuild.message" class="rebuild-message">{{ rebuild.message }}</p>
            </template>
            <p v-else class="overview-item__value text-muted">当前没有重建任务</p>
          </div>
        </div>
      </NSpin>
    </NCard>

    <NCard
      title="版本索引"
      :bordered="false"
      size="small"
      class="page-section sm:flex-1-hidden"
      content-class="flex-col-stretch min-h-0 sm:h-full"
    >
      <template #header-extra>
        <NSpace :size="12" align="center" wrap>
          <NButton type="primary" :disabled="rebuild?.state === 'RUNNING'" @click="openRebuildDialog">触发重建</NButton>
          <NButton :loading="loading" @click="getOverview">刷新</NButton>
        </NSpace>
      </template>

      <div class="min-h-0 sm:flex-1">
        <NDataTable
          :bordered="false"
          :columns="columns"
          :data="overview?.indices || []"
          size="small"
          :flex-height="!appStore.isMobile"
          :scroll-x="1000"
          :loading="loading"
          :row-key="row => row.name"
          class="sm:h-full"
        >
          <template #empty>
            <div class="table-state">
              <div class="app-state-badge">
                <icon-lucide:package class="text-icon-large" />
              </div>
              <p class="table-state__title">暂无版本索引</p>
              <p class="table-state__desc">首次文档向量化后会自动创建版本索引；切换 embedding 模型前可先触发重建</p>
            </div>
          </template>
        </NDataTable>
      </div>
    </NCard>

    <NModal
      v-model:show="rebuildVisible"
      preset="dialog"
      title="触发 embedding 重建"
      :show-icon="false"
      :mask-closable="false"
      class="w-560px!"
    >
      <NForm label-placement="left" :label-width="96" :show-feedback="false" class="mt-10px">
        <NAlert type="info" :show-icon="false" class="mb-12px">
          用目标模型对全部文档重新生成向量并写入新版本索引，完成后原子切换线上别名；期间检索不受影响，旧索引保留可回滚。
        </NAlert>
        <NFormItem label="目标供应商" required>
          <NSelect
            v-model:value="rebuildProvider"
            :options="providerOptions"
            :loading="providerLoading"
            placeholder="选择已在模型供应商页配好维度的 embedding 供应商"
          />
        </NFormItem>
        <NFormItem label="全新重建">
          <div class="flex items-center gap-8px">
            <NSwitch v-model:value="rebuildFresh" />
            <span class="text-muted text-xs">开启后删除同名目标索引从头建；关闭则在已有目标索引上续建（断点续跑）</span>
          </div>
        </NFormItem>
      </NForm>
      <template #action>
        <NSpace :size="12">
          <NButton @click="rebuildVisible = false">取消</NButton>
          <NButton type="primary" :loading="rebuildSubmitting" @click="handleStartRebuild">提交任务</NButton>
        </NSpace>
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

.overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
}

.overview-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 16px;
  border-radius: var(--app-radius-sm, 8px);
  background: var(--app-fill-subtle);
}

.overview-item--wide {
  grid-column: 1 / -1;
}

.overview-item__label {
  margin: 0;
  font-size: 12px;
  color: var(--app-muted);
}

.overview-item__value {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--app-text);
  word-break: break-all;
}

.rebuild-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.rebuild-message {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--app-muted);
}
</style>
