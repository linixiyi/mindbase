<script setup lang="tsx">
import type { DataTableColumns } from 'naive-ui';
import { NButton, NProgress, NTag } from 'naive-ui';
import { fetchGetRagEvalReport, fetchGetRagEvalRuns, fetchRunRagEval } from '@/service/api';

const appStore = useAppStore();

const runStateTag: Record<Api.RagEval.RunState, 'info' | 'success' | 'error'> = {
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'error'
};

const runStateLabel: Record<Api.RagEval.RunState, string> = {
  RUNNING: '运行中',
  SUCCEEDED: '已完成',
  FAILED: '失败'
};

const loading = ref(false);
const runs = ref<Api.RagEval.RunStatus[]>([]);

function progressOf(row: Api.RagEval.RunStatus) {
  if (row.totalCases <= 0) return 0;
  return Math.round((row.completedCases / row.totalCases) * 100);
}

const columns = computed<DataTableColumns<Api.RagEval.RunStatus>>(() => [
  {
    key: 'runId',
    title: '任务',
    minWidth: 220,
    render: row => (
      <div class="min-w-0">
        <div class="truncate text-3 font-mono">{row.runId}</div>
        <div class="text-muted truncate text-xs">
          {row.label || 'default'} · topK {row.topK}
        </div>
      </div>
    )
  },
  {
    key: 'datasetSource',
    title: '数据集',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: row => row.datasetSource || '-'
  },
  {
    key: 'state',
    title: '状态',
    width: 100,
    render: row => (
      <NTag bordered={false} type={runStateTag[row.state]}>
        {runStateLabel[row.state]}
      </NTag>
    )
  },
  {
    key: 'progress',
    title: '进度',
    width: 170,
    render: row =>
      row.state === 'RUNNING' ? (
        <div class="flex items-center gap-2">
          <NProgress type="line" percentage={progressOf(row)} height={6} class="w-100px" />
          <span class="text-muted text-xs">
            {row.completedCases}/{row.totalCases}
          </span>
        </div>
      ) : (
        <span class="text-xs">
          {row.completedCases}/{row.totalCases} 例
        </span>
      )
  },
  {
    key: 'operator',
    title: '发起人',
    width: 110,
    render: row => row.operator || '-'
  },
  {
    key: 'startedAt',
    title: '开始/结束',
    width: 180,
    render: row => (
      <div class="text-xs">
        <div>{dayjs(row.startedAt).format('MM-DD HH:mm:ss')}</div>
        <div class="text-muted">{row.finishedAt ? dayjs(row.finishedAt).format('MM-DD HH:mm:ss') : '-'}</div>
      </div>
    )
  },
  {
    key: 'operate',
    title: '操作',
    width: 100,
    render: row => renderOperate(row)
  }
]);

function renderOperate(row: Api.RagEval.RunStatus) {
  if (row.state === 'SUCCEEDED') {
    return (
      <NButton type="primary" quaternary size="small" onClick={() => openReport(row.runId)}>
        查看报告
      </NButton>
    );
  }
  if (row.state === 'FAILED') {
    return (
      <span class="text-muted text-xs" title={row.message || ''}>
        {row.message ? row.message.slice(0, 12) : '失败'}
      </span>
    );
  }
  return <span class="text-muted text-xs">进行中</span>;
}

async function getRuns() {
  loading.value = true;

  const { data: payload, error } = await fetchGetRagEvalRuns();

  if (!error && payload) {
    runs.value = payload || [];
  }

  loading.value = false;
}

let runTimer: ReturnType<typeof setInterval> | null = null;

function syncRunTimer() {
  const anyRunning = runs.value.some(run => run.state === 'RUNNING');
  if (anyRunning && runTimer === null) {
    runTimer = setInterval(getRuns, 5000);
  } else if (!anyRunning && runTimer !== null) {
    clearInterval(runTimer);
    runTimer = null;
  }
}

watch(runs, syncRunTimer, { immediate: true });

onUnmounted(() => {
  if (runTimer !== null) {
    clearInterval(runTimer);
    runTimer = null;
  }
});

const formModel = reactive({
  label: '',
  topK: null as number | null,
  sourceType: 'path' as 'path' | 'jsonl',
  datasetPath: '',
  jsonl: ''
});

const submitting = ref(false);

async function handleRun() {
  const datasetPath = formModel.sourceType === 'path' ? formModel.datasetPath.trim() : '';
  const jsonl = formModel.sourceType === 'jsonl' ? formModel.jsonl.trim() : '';

  if (!datasetPath && !jsonl) {
    window.$message?.warning(formModel.sourceType === 'path' ? '请填写数据集路径' : '请粘贴 JSONL 数据集内容');
    return;
  }

  submitting.value = true;
  const { error } = await fetchRunRagEval({
    datasetPath: datasetPath || undefined,
    jsonl: jsonl || undefined,
    topK: formModel.topK ?? undefined,
    label: formModel.label.trim() || undefined
  });

  if (!error) {
    window.$message?.success('评测任务已提交，进度见下方任务列表');
    await getRuns();
  }

  submitting.value = false;
}

const reportVisible = ref(false);
const reportLoading = ref(false);
const report = ref<Api.RagEval.ReportPayload | null>(null);

function percent(value: number | null | undefined) {
  return value === null || value === undefined ? '-' : `${(value * 100).toFixed(1)}%`;
}

async function openReport(runId: string) {
  reportVisible.value = true;
  reportLoading.value = true;
  report.value = null;

  const { data: payload, error } = await fetchGetRagEvalReport(runId);

  if (!error && payload) {
    report.value = payload;
  }

  reportLoading.value = false;
}

onMounted(() => {
  getRuns();
});
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch overflow-auto">
    <h1 class="page-title">离线评测</h1>

    <NCard title="发起评测" :bordered="false" size="small" class="page-section">
      <NForm label-placement="left" :label-width="96" :show-feedback="false" inline @submit.prevent="handleRun">
        <NFormItem label="标签">
          <NInput
            v-model:value="formModel.label"
            placeholder="区分同数据集的多次评测（可选）"
            maxlength="40"
            class="w-200px!"
          />
        </NFormItem>
        <NFormItem label="检索 topK">
          <NInputNumber
            v-model:value="formModel.topK"
            :min="1"
            :precision="0"
            placeholder="默认跟随配置"
            class="w-160px!"
          />
        </NFormItem>
        <NFormItem label="数据集来源">
          <NRadioGroup v-model:value="formModel.sourceType">
            <NRadio value="path">服务器数据集</NRadio>
            <NRadio value="jsonl">粘贴 JSONL</NRadio>
          </NRadioGroup>
        </NFormItem>
        <div class="filter-actions">
          <NButton type="primary" attr-type="submit" :loading="submitting">运行评测</NButton>
        </div>
      </NForm>
      <NFormItem
        v-if="formModel.sourceType === 'path'"
        label="数据集路径"
        label-placement="left"
        :show-feedback="false"
      >
        <NInput
          v-model:value="formModel.datasetPath"
          placeholder="如 classpath:eval/rag-basic.jsonl，或 eval.dataset-dir 下的相对路径"
          class="w-480px!"
        />
      </NFormItem>
      <NInput
        v-else
        v-model:value="formModel.jsonl"
        type="textarea"
        placeholder='每行一个 JSON 用例：{"query": "...", "expectedDocMd5": "..."}'
        :rows="5"
        class="mt-10px"
      />
      <p class="dataset-hint">
        评测以当前管理员身份执行，检索权限与线上完全一致；任务异步运行，完成后可在下方查看报告（检索命中率、MRR、评委准确率与人工一致率）。
      </p>
    </NCard>

    <NCard
      title="评测任务"
      :bordered="false"
      size="small"
      class="page-section sm:flex-1-hidden"
      content-class="flex-col-stretch min-h-0 sm:h-full"
    >
      <template #header-extra>
        <NButton :loading="loading" @click="getRuns">刷新</NButton>
      </template>

      <div class="min-h-0 sm:flex-1">
        <NDataTable
          :bordered="false"
          :columns="columns"
          :data="runs"
          size="small"
          :flex-height="!appStore.isMobile"
          :scroll-x="1100"
          :loading="loading"
          :row-key="row => row.runId"
          class="sm:h-full"
        >
          <template #empty>
            <div class="table-state">
              <div class="app-state-badge">
                <icon-lucide:shield-check class="text-icon-large" />
              </div>
              <p class="table-state__title">暂无评测任务</p>
              <p class="table-state__desc">准备一份问答数据集后从上方发起首次评测，量化检索与回答质量</p>
            </div>
          </template>
        </NDataTable>
      </div>
    </NCard>

    <NModal v-model:show="reportVisible" preset="dialog" title="评测报告" :show-icon="false" class="w-720px!">
      <NSpin :show="reportLoading">
        <div v-if="report" class="report-body">
          <div class="report-meta">
            <NTag :bordered="false" type="info">{{ report.label || 'default' }}</NTag>
            <span class="text-xs font-mono">{{ report.runId }}</span>
            <span class="text-muted text-xs">topK {{ report.topK }} · {{ report.datasetSource }}</span>
          </div>
          <div class="metric-grid">
            <div class="metric">
              <p class="metric__label">用例总数</p>
              <p class="metric__value">{{ report.summary.totalCases }}</p>
            </div>
            <div class="metric">
              <p class="metric__label">检索命中数</p>
              <p class="metric__value">{{ report.summary.retrievalHits }}/{{ report.summary.labeledCases }}</p>
            </div>
            <div class="metric">
              <p class="metric__label">Recall@K</p>
              <p class="metric__value">{{ percent(report.summary.recallAtK) }}</p>
            </div>
            <div class="metric">
              <p class="metric__label">MRR</p>
              <p class="metric__value">
                {{
                  report.summary.mrr === null || report.summary.mrr === undefined ? '-' : report.summary.mrr.toFixed(3)
                }}
              </p>
            </div>
            <div class="metric">
              <p class="metric__label">文档 Recall@K</p>
              <p class="metric__value">{{ percent(report.summary.docRecallAtK) }}</p>
            </div>
            <div class="metric">
              <p class="metric__label">评委准确率</p>
              <p class="metric__value">{{ percent(report.summary.accuracy) }}</p>
            </div>
            <div class="metric">
              <p class="metric__label">评委平均分</p>
              <p class="metric__value">
                {{
                  report.summary.meanJudgeScore === null || report.summary.meanJudgeScore === undefined
                    ? '-'
                    : report.summary.meanJudgeScore.toFixed(2)
                }}
              </p>
            </div>
            <div class="metric">
              <p class="metric__label">需人工复核</p>
              <p class="metric__value">{{ report.summary.needsHumanReviewCount }}</p>
            </div>
            <div class="metric">
              <p class="metric__label">失败用例</p>
              <p class="metric__value">{{ report.summary.failedCases }}</p>
            </div>
          </div>
          <NAlert
            v-if="report.humanReview?.agreement"
            :type="report.humanReview.agreement.status === 'NEEDS_RECALIBRATION' ? 'warning' : 'default'"
            :show-icon="false"
          >
            人工一致率 {{ percent(report.humanReview.agreement.agreementRate) }}（已复核
            {{ report.humanReview.agreement.reviewedCases }} 例）{{ report.humanReview.agreement.warning || '' }}
          </NAlert>
        </div>
      </NSpin>
      <template #action>
        <NButton @click="reportVisible = false">关闭</NButton>
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

.dataset-hint {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-muted);
}

.report-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.report-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-radius: var(--app-radius-sm, 8px);
  background: var(--app-fill-subtle);
}

.metric__label {
  margin: 0;
  font-size: 12px;
  color: var(--app-muted);
}

.metric__value {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--app-text);
}
</style>
