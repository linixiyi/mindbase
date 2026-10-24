<script setup lang="tsx">
import { computed, onMounted, ref, watch } from 'vue';
import { NButton, NCard, NEmpty, NTag, useThemeVars } from 'naive-ui';
import type { ECOption } from '@/hooks/common/echarts';
import { useEcharts } from '@/hooks/common/echarts';

const activePanel = ref('overview');
const trendWindow = ref<7 | 30>(7);
const overviewLoading = ref(false);
const overview = ref<Api.Admin.UsageOverview | null>(null);
const overviewError = ref('');
const rateLimitLoading = ref(false);
const rateLimitSaving = ref(false);
const rateLimits = ref<Api.Admin.RateLimitSettings | null>(null);

// Chart series colors derive from the active Naive theme (token pipeline), so both
// themes and the indigo accent stay consistent without hardcoding hex values.
const themeVars = useThemeVars();
const chartPalette = computed(() => ({
  llm: themeVars.value.primaryColor,
  embedding: themeVars.value.textColor2,
  request: themeVars.value.textColor3
}));

async function getOverview() {
  overviewLoading.value = true;
  const { error, data } = await request<Api.Admin.UsageOverview>({
    url: '/admin/usage/overview',
    params: {
      days: trendWindow.value
    }
  });

  if (!error && data) {
    overview.value = data;
    overviewError.value = '';
  } else if (!overview.value) {
    overviewError.value = '无法获取用量数据，请检查网络后重试';
  }
  overviewLoading.value = false;
}

function createDefaultRateLimitSettings(): Api.Admin.RateLimitSettings {
  return {
    chatMessage: {
      max: 30,
      windowSeconds: 60
    },
    llmGlobalToken: {
      minuteMax: 120000,
      minuteWindowSeconds: 60,
      dayMax: 8000000,
      dayWindowSeconds: 86400
    },
    embeddingUploadToken: {
      minuteMax: 200000,
      minuteWindowSeconds: 60,
      dayMax: 20000000,
      dayWindowSeconds: 86400
    },
    embeddingQueryRequest: {
      minuteMax: 60,
      minuteWindowSeconds: 60,
      dayMax: 5000,
      dayWindowSeconds: 86400
    },
    embeddingQueryGlobalToken: {
      minuteMax: 60000,
      minuteWindowSeconds: 60,
      dayMax: 4000000,
      dayWindowSeconds: 86400
    }
  };
}

function cloneTokenBudgetLimit(payload?: Partial<Api.Admin.TokenBudgetLimit> | null): Api.Admin.TokenBudgetLimit {
  return {
    minuteMax: Number(payload?.minuteMax || 0),
    minuteWindowSeconds: Number(payload?.minuteWindowSeconds || 0),
    dayMax: Number(payload?.dayMax || 0),
    dayWindowSeconds: Number(payload?.dayWindowSeconds || 0)
  };
}

function cloneDualWindowLimit(payload?: Partial<Api.Admin.DualWindowLimit> | null): Api.Admin.DualWindowLimit {
  return {
    minuteMax: Number(payload?.minuteMax || 0),
    minuteWindowSeconds: Number(payload?.minuteWindowSeconds || 0),
    dayMax: Number(payload?.dayMax || 0),
    dayWindowSeconds: Number(payload?.dayWindowSeconds || 0)
  };
}

function cloneRateLimitSettings(payload?: Api.Admin.RateLimitSettings | null): Api.Admin.RateLimitSettings {
  return {
    chatMessage: {
      max: Number(payload?.chatMessage.max || 0),
      windowSeconds: Number(payload?.chatMessage.windowSeconds || 0)
    },
    llmGlobalToken: cloneTokenBudgetLimit(payload?.llmGlobalToken),
    embeddingUploadToken: cloneTokenBudgetLimit(payload?.embeddingUploadToken),
    embeddingQueryRequest: cloneDualWindowLimit(payload?.embeddingQueryRequest),
    embeddingQueryGlobalToken: cloneTokenBudgetLimit(payload?.embeddingQueryGlobalToken)
  };
}

async function getRateLimits() {
  rateLimitLoading.value = true;
  const { error, data } = await request<Api.Admin.RateLimitSettings>({
    url: '/admin/rate-limits'
  });

  if (!error && data) {
    rateLimits.value = cloneRateLimitSettings(data);
  } else if (!rateLimits.value) {
    rateLimits.value = createDefaultRateLimitSettings();
  }
  rateLimitLoading.value = false;
}

async function submitRateLimits() {
  if (!rateLimits.value) {
    return;
  }

  rateLimitSaving.value = true;
  const { error, data } = await request<Api.Admin.RateLimitSettings>({
    url: '/admin/rate-limits',
    method: 'put',
    data: cloneRateLimitSettings(rateLimits.value)
  });

  if (!error && data) {
    rateLimits.value = cloneRateLimitSettings(data);
    window.$message?.success('限流配置已更新');
  }

  rateLimitSaving.value = false;
}

const { domRef: trendChartRef, updateOptions } = useEcharts<ECOption>(() => ({
  tooltip: {
    trigger: 'axis'
  },
  legend: {
    type: 'scroll',
    top: 0
  },
  grid: {
    top: 48,
    left: 24,
    right: 24,
    bottom: 24,
    containLabel: true
  },
  xAxis: {
    type: 'category',
    data: []
  },
  yAxis: [
    {
      type: 'value',
      name: 'Tokens'
    },
    {
      type: 'value',
      name: 'Requests'
    }
  ],
  series: []
}));

watch([overview, trendWindow, chartPalette], async () => {
  const trends = overview.value?.trends || [];
  const { llm, embedding, request } = chartPalette.value;
  await updateOptions(() => ({
    tooltip: {
      trigger: 'axis'
    },
    legend: {
      type: 'scroll',
      top: 0
    },
    grid: {
      top: 48,
      left: 24,
      right: 24,
      bottom: 24,
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: trends.map(item => dayjs(item.day).format('MM-DD'))
    },
    yAxis: [
      {
        type: 'value',
        name: 'Tokens'
      },
      {
        type: 'value',
        name: 'Requests'
      }
    ],
    series: [
      {
        name: 'LLM Tokens',
        type: 'line',
        smooth: true,
        color: llm,
        data: trends.map(item => item.llmUsedTokens)
      },
      {
        name: 'Embedding Tokens',
        type: 'line',
        smooth: true,
        color: embedding,
        data: trends.map(item => item.embeddingUsedTokens)
      },
      {
        name: 'Chat Messages',
        type: 'bar',
        yAxisIndex: 1,
        barMaxWidth: 18,
        color: llm,
        itemStyle: {
          opacity: 0.4
        },
        data: trends.map(item => item.chatRequestCount)
      },
      {
        name: 'LLM Requests',
        type: 'bar',
        yAxisIndex: 1,
        barMaxWidth: 18,
        color: request,
        itemStyle: {
          opacity: 0.32
        },
        data: trends.map(item => item.llmRequestCount)
      },
      {
        name: 'Embedding Requests',
        type: 'bar',
        yAxisIndex: 1,
        barMaxWidth: 18,
        color: request,
        itemStyle: {
          opacity: 0.22
        },
        data: trends.map(item => item.embeddingRequestCount)
      }
    ]
  }));
});

const alertCount = computed(() => overview.value?.alerts.length || 0);
const criticalAlertCount = computed(() => overview.value?.alerts.filter(item => item.level === 'critical').length || 0);
const todaySummary = computed(() => overview.value?.today);

function formatNumber(value?: number) {
  return Number(value || 0).toLocaleString();
}

function scopeLabel(scope: 'llm' | 'embedding') {
  return scope === 'llm' ? 'LLM' : 'Embedding';
}

function alertType(level: 'critical' | 'warning') {
  return level === 'critical' ? 'error' : 'warning';
}

onMounted(() => {
  getOverview();
  getRateLimits();
});
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch overflow-auto">
    <h1 class="page-title">用量监控</h1>
    <NTabs v-model:value="activePanel" type="line" size="large">
      <NTab name="overview">用量总览</NTab>
      <NTab name="limits">限流设置</NTab>
    </NTabs>
    <NCard v-show="activePanel === 'limits'" :bordered="false" size="small" class="page-section">
      <template #header>调用限流配置</template>
      <template #header-extra>
        <div class="flex items-center gap-2">
          <NButton type="primary" size="small" :loading="rateLimitSaving" @click="submitRateLimits">保存配置</NButton>
        </div>
      </template>

      <NSpin :show="rateLimitLoading">
        <div v-if="rateLimits" class="limits-layout">
          <div class="limit-card">
            <div class="limit-title">聊天消息</div>
            <div class="limit-grid">
              <div>
                <div class="limit-label">次数上限</div>
                <NInputNumber v-model:value="rateLimits.chatMessage.max" :min="1" class="w-full" />
              </div>
              <div>
                <div class="limit-label">窗口秒数</div>
                <NInputNumber v-model:value="rateLimits.chatMessage.windowSeconds" :min="1" class="w-full" />
              </div>
            </div>
          </div>

          <div class="limit-card">
            <div class="limit-title">LLM 全网 Token 预算</div>
            <div class="limit-grid">
              <div>
                <div class="limit-label">分钟 Token 上限</div>
                <NInputNumber v-model:value="rateLimits.llmGlobalToken.minuteMax" :min="1" class="w-full" />
              </div>
              <div>
                <div class="limit-label">分钟窗口秒数</div>
                <NInputNumber v-model:value="rateLimits.llmGlobalToken.minuteWindowSeconds" :min="1" class="w-full" />
              </div>
              <div>
                <div class="limit-label">日 Token 上限</div>
                <NInputNumber v-model:value="rateLimits.llmGlobalToken.dayMax" :min="1" class="w-full" />
              </div>
              <div>
                <div class="limit-label">日窗口秒数</div>
                <NInputNumber v-model:value="rateLimits.llmGlobalToken.dayWindowSeconds" :min="1" class="w-full" />
              </div>
            </div>
          </div>

          <div class="limit-card">
            <div class="limit-title">Embedding 上传 Token 预算</div>
            <div class="limit-grid">
              <div>
                <div class="limit-label">分钟 Token 上限</div>
                <NInputNumber v-model:value="rateLimits.embeddingUploadToken.minuteMax" :min="1" class="w-full" />
              </div>
              <div>
                <div class="limit-label">分钟窗口秒数</div>
                <NInputNumber
                  v-model:value="rateLimits.embeddingUploadToken.minuteWindowSeconds"
                  :min="1"
                  class="w-full"
                />
              </div>
              <div>
                <div class="limit-label">日 Token 上限</div>
                <NInputNumber v-model:value="rateLimits.embeddingUploadToken.dayMax" :min="1" class="w-full" />
              </div>
              <div>
                <div class="limit-label">日窗口秒数</div>
                <NInputNumber
                  v-model:value="rateLimits.embeddingUploadToken.dayWindowSeconds"
                  :min="1"
                  class="w-full"
                />
              </div>
            </div>
          </div>

          <div class="limit-card">
            <div class="limit-title">Embedding 查询</div>
            <div class="limit-grid">
              <div>
                <div class="limit-label">单用户分钟次数</div>
                <NInputNumber v-model:value="rateLimits.embeddingQueryRequest.minuteMax" :min="1" class="w-full" />
              </div>
              <div>
                <div class="limit-label">分钟窗口秒数</div>
                <NInputNumber
                  v-model:value="rateLimits.embeddingQueryRequest.minuteWindowSeconds"
                  :min="1"
                  class="w-full"
                />
              </div>
              <div>
                <div class="limit-label">单用户日次数</div>
                <NInputNumber v-model:value="rateLimits.embeddingQueryRequest.dayMax" :min="1" class="w-full" />
              </div>
              <div>
                <div class="limit-label">日窗口秒数</div>
                <NInputNumber
                  v-model:value="rateLimits.embeddingQueryRequest.dayWindowSeconds"
                  :min="1"
                  class="w-full"
                />
              </div>
              <div>
                <div class="limit-label">全网分钟 Token</div>
                <NInputNumber v-model:value="rateLimits.embeddingQueryGlobalToken.minuteMax" :min="1" class="w-full" />
              </div>
              <div>
                <div class="limit-label">查询分钟窗口秒数</div>
                <NInputNumber
                  v-model:value="rateLimits.embeddingQueryGlobalToken.minuteWindowSeconds"
                  :min="1"
                  class="w-full"
                />
              </div>
              <div>
                <div class="limit-label">全网日 Token</div>
                <NInputNumber v-model:value="rateLimits.embeddingQueryGlobalToken.dayMax" :min="1" class="w-full" />
              </div>
              <div>
                <div class="limit-label">查询日窗口秒数</div>
                <NInputNumber
                  v-model:value="rateLimits.embeddingQueryGlobalToken.dayWindowSeconds"
                  :min="1"
                  class="w-full"
                />
              </div>
            </div>
          </div>
        </div>
        <!-- R11 delight 复审：紧凑数据槽空态去灰盒插画（show-icon=false），徽标 tile 在窄列里过重 -->
        <NEmpty v-else size="small" :show-icon="false" description="暂无限流配置" />
      </NSpin>
    </NCard>

    <NCard v-show="activePanel === 'overview'" :bordered="false" size="small" class="page-section">
      <template #header>
        <div class="flex items-center gap-3">
          <span>用量总览</span>
          <NTag size="small" type="warning">今日告警 {{ alertCount }}</NTag>
          <NTag size="small" type="error">超额 {{ criticalAlertCount }}</NTag>
        </div>
      </template>
      <template #header-extra>
        <div class="flex items-center gap-2">
          <NButton
            size="small"
            :type="trendWindow === 7 ? 'primary' : 'default'"
            @click="
              trendWindow = 7;
              getOverview();
            "
          >
            近7天
          </NButton>
          <NButton
            size="small"
            :type="trendWindow === 30 ? 'primary' : 'default'"
            @click="
              trendWindow = 30;
              getOverview();
            "
          >
            近30天
          </NButton>
        </div>
      </template>

      <NSpin :show="overviewLoading">
        <div v-if="overviewError && !overview" class="usage-state usage-state--error">
          <div class="app-state-badge app-state-badge--error">
            <icon-lucide:circle-alert class="text-icon-large" />
          </div>
          <p class="usage-state__title">用量数据加载失败</p>
          <p class="usage-state__desc">{{ overviewError }}</p>
          <NButton size="small" :disabled="overviewLoading" @click="getOverview">
            <template #icon>
              <icon-lucide:rotate-cw />
            </template>
            重试
          </NButton>
        </div>
        <div v-else class="flex flex-col gap-4">
          <div class="summary-grid">
            <div class="summary-card">
              <div class="summary-label">今日聊天消息</div>
              <div class="summary-value">{{ formatNumber(todaySummary?.chatRequestCount) }}</div>
              <div class="summary-sub">按通过限流的消息数统计</div>
            </div>
            <div class="summary-card">
              <div class="summary-label">今日 LLM Tokens</div>
              <div class="summary-value">{{ formatNumber(todaySummary?.llmUsedTokens) }}</div>
              <div class="summary-sub">请求 {{ formatNumber(todaySummary?.llmRequestCount) }} 次</div>
            </div>
            <div class="summary-card">
              <div class="summary-label">今日 Embedding Tokens</div>
              <div class="summary-value">{{ formatNumber(todaySummary?.embeddingUsedTokens) }}</div>
              <div class="summary-sub">请求 {{ formatNumber(todaySummary?.embeddingRequestCount) }} 次</div>
            </div>
            <div class="summary-card is-alert">
              <div class="summary-label">高风险用户</div>
              <div class="summary-value">{{ criticalAlertCount }}</div>
              <div class="summary-sub">额度已耗尽</div>
            </div>
            <div class="summary-card">
              <div class="summary-label">总告警数</div>
              <div class="summary-value">{{ alertCount }}</div>
              <div class="summary-sub">含 80% 以上预警</div>
            </div>
          </div>

          <div class="grid gap-4 xl:grid-cols-[minmax(0,1.4fr)_minmax(320px,0.6fr)]">
            <NCard size="small" :bordered="false" class="overview-section">
              <template #header>调用趋势</template>
              <div ref="trendChartRef" class="h-360px w-full" />
            </NCard>

            <div class="flex flex-col gap-4">
              <NCard size="small" :bordered="false" class="overview-section">
                <template #header>超额与预警</template>
                <div v-if="overview?.alerts?.length" class="flex flex-col gap-3">
                  <div
                    v-for="alert in overview.alerts.slice(0, 6)"
                    :key="`${alert.userId}-${alert.scope}`"
                    class="alert-item"
                  >
                    <div class="flex items-center justify-between gap-3">
                      <div class="flex items-center gap-2">
                        <span class="text-body font-medium">{{ alert.username }}</span>
                        <NTag size="small" :type="alertType(alert.level)">{{ scopeLabel(alert.scope) }}</NTag>
                      </div>
                      <NTag size="small" :type="alertType(alert.level)">{{ alert.message }}</NTag>
                    </div>
                    <div class="text-muted text-xs">
                      {{ formatNumber(alert.usedTokens) }} / {{ formatNumber(alert.limitTokens) }}，剩余
                      {{ formatNumber(alert.remainingTokens) }}，{{ alert.requestCount }} 次
                    </div>
                  </div>
                </div>
                <NEmpty v-else size="small" :show-icon="false" description="暂无告警" />
              </NCard>

              <NCard size="small" :bordered="false" class="overview-section">
                <template #header>今日用量排行</template>
                <div class="flex flex-col gap-4">
                  <div>
                    <div class="usage-scope-label mb-2">LLM</div>
                    <div v-if="overview?.llmRankings?.length" class="flex flex-col gap-2">
                      <div
                        v-for="(item, index) in overview.llmRankings"
                        :key="`llm-${item.userId}`"
                        class="ranking-item"
                      >
                        <span class="ranking-index">{{ index + 1 }}</span>
                        <div class="flex-1">
                          <div class="text-body font-medium">{{ item.username }}</div>
                          <div class="text-muted text-xs">
                            {{ formatNumber(item.usedTokens) }} / {{ formatNumber(item.limitTokens) }} ·
                            {{ item.requestCount }} 次
                          </div>
                        </div>
                      </div>
                    </div>
                    <NEmpty v-else size="small" :show-icon="false" description="暂无数据" />
                  </div>

                  <div>
                    <div class="usage-scope-label mb-2">Embedding</div>
                    <div v-if="overview?.embeddingRankings?.length" class="flex flex-col gap-2">
                      <div
                        v-for="(item, index) in overview.embeddingRankings"
                        :key="`emb-${item.userId}`"
                        class="ranking-item"
                      >
                        <span class="ranking-index">{{ index + 1 }}</span>
                        <div class="flex-1">
                          <div class="text-body font-medium">{{ item.username }}</div>
                          <div class="text-muted text-xs">
                            {{ formatNumber(item.usedTokens) }} / {{ formatNumber(item.limitTokens) }} ·
                            {{ item.requestCount }} 次
                          </div>
                        </div>
                      </div>
                    </div>
                    <NEmpty v-else size="small" :show-icon="false" description="暂无数据" />
                  </div>
                </div>
              </NCard>
            </div>
          </div>
        </div>
      </NSpin>
    </NCard>
  </div>
</template>

<style scoped lang="scss">
.limits-layout {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 40px 56px;
  padding-top: 8px;
}
.limit-card {
  min-width: 0;
}
.limit-title {
  margin-bottom: 20px;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--app-text);
}
.limit-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}
.limit-label {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--app-muted);
}
.limits-layout :deep(.n-input__input-el),
.limits-layout :deep(.n-input .n-input__placeholder) {
  font-variant-numeric: tabular-nums;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16px;
}
.summary-card {
  padding: 16px 12px 24px;
  min-width: 0;
}
.summary-label {
  font-size: 12px;
  color: var(--app-muted);
}
.summary-value {
  margin-top: 12px;
  font-size: 28px;
  line-height: 1.25;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--app-text);
}
/* Semantic depth rule: warning text on a light surface reads at -700, dark at -400 */
.summary-card.is-alert .summary-value {
  color: rgb(var(--warning-700-color));
}
html.dark .summary-card.is-alert .summary-value {
  color: rgb(var(--warning-400-color));
}
.summary-sub {
  margin-top: 8px;
  font-size: 12px;
  color: var(--app-muted);
}
.usage-scope-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--app-text-secondary);
}
.overview-section {
  background: transparent;
  border-radius: 0;
}
.overview-section :deep(.n-card-header) {
  padding: 16px 0;
}
.overview-section :deep(.n-card__content) {
  padding: 0;
}
.alert-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--app-border);
}
.ranking-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
}
.ranking-index {
  width: 24px;
  text-align: center;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--app-muted);
}
.usage-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 320px;
  padding: 48px 24px;
  text-align: center;
  animation: usage-state-in var(--app-dur-base) var(--app-ease-standard);
}
/* R11 delight：空态图标改吃 global.scss 的 azure 徽标 tile（.app-state-badge，error 变体
   走语义红 wash）——裸灰 ghost 图标就此从空态退役，__icon 规则随之删除 */
.usage-state__title {
  margin: 8px 0 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--app-text);
}
.usage-state__desc {
  margin: 0;
  max-width: 360px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--app-muted);
}
@keyframes usage-state-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@media (max-width: 900px) {
  .summary-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .limits-layout {
    grid-template-columns: minmax(0, 1fr);
    gap: 32px;
  }
}
@media (max-width: 640px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
  .summary-card {
    padding: 12px 0;
  }
  .summary-value {
    font-size: 24px;
    overflow-wrap: anywhere;
  }
}
@media (max-width: 480px) {
  .limit-grid {
    grid-template-columns: minmax(0, 1fr);
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
</style>
