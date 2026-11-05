<script setup lang="tsx">
import { computed, onMounted, ref } from 'vue';
import { NButton, NTag } from 'naive-ui';

const modelProvidersLoading = ref(false);
const modelProvidersSaving = ref(false);
const modelProvidersError = ref('');
const modelProviders = ref<Api.Admin.ModelProviderSettings | null>(null);
const selectedScope = ref<'llm' | 'embedding' | 'rerank'>('llm');
const selectedProviders = ref({ llm: '', embedding: '' });
/** rerank 页签下 provider 编辑器不渲染，索引回落到 llm 仅为满足类型（不会出现在模板中） */
const providerScope = computed<'llm' | 'embedding'>(() =>
  selectedScope.value === 'rerank' ? 'llm' : selectedScope.value
);
const currentScope = computed(() => modelProviders.value?.[providerScope.value]);
const currentProvider = computed(() => {
  const scope = currentScope.value;
  return (
    scope?.providers.find(item => item.provider === selectedProviders.value[providerScope.value]) ||
    scope?.providers.find(item => item.provider === scope.activeProvider) ||
    scope?.providers[0]
  );
});
const providerOptions = computed(
  () =>
    currentScope.value?.providers.map(item => ({
      label: item.displayName,
      value: item.provider,
      disabled: !item.enabled
    })) || []
);

function cloneProviderItem(item: Api.Admin.ModelProviderItem): Api.Admin.ModelProviderItem {
  return {
    provider: item.provider,
    displayName: item.displayName,
    apiStyle: item.apiStyle,
    apiBaseUrl: item.apiBaseUrl,
    model: item.model,
    dimension: item.dimension ?? null,
    enabled: Boolean(item.enabled),
    active: Boolean(item.active),
    hasApiKey: Boolean(item.hasApiKey),
    maskedApiKey: item.maskedApiKey || '',
    apiKeyInput: ''
  };
}

function cloneModelProviderScope(payload: Api.Admin.ModelProviderScopeSettings): Api.Admin.ModelProviderScopeSettings {
  return {
    scope: payload.scope,
    activeProvider: payload.activeProvider,
    providers: (payload.providers || []).map(cloneProviderItem)
  };
}

function cloneModelProviderSettings(
  payload?: Api.Admin.ModelProviderSettings | null
): Api.Admin.ModelProviderSettings | null {
  if (!payload) {
    return null;
  }
  return {
    llm: cloneModelProviderScope(payload.llm),
    embedding: cloneModelProviderScope(payload.embedding)
  };
}

async function getModelProviders() {
  modelProvidersLoading.value = true;
  const { error, data } = await request<Api.Admin.ModelProviderSettings>({
    url: '/admin/model-providers'
  });

  if (!error && data) {
    modelProviders.value = cloneModelProviderSettings(data);
    modelProvidersError.value = '';
  } else if (!modelProviders.value) {
    modelProvidersError.value = '无法获取模型供应商配置，请检查网络后重试';
  }
  modelProvidersLoading.value = false;
}

function buildProviderPayload(scope: Api.Admin.ModelProviderScopeSettings) {
  return {
    activeProvider: scope.activeProvider,
    providers: scope.providers.map(item => ({
      provider: item.provider,
      apiBaseUrl: item.apiBaseUrl,
      model: item.model,
      apiKey: item.apiKeyInput?.trim() || '',
      dimension: scope.scope === 'embedding' ? item.dimension : null,
      enabled: item.enabled
    }))
  };
}

async function submitModelProviders(scopeKey: 'llm' | 'embedding') {
  const scope = modelProviders.value?.[scopeKey];
  if (!scope) {
    return;
  }

  modelProvidersSaving.value = true;
  const { error, data } = await request<Api.Admin.ModelProviderScopeSettings>({
    url: `/admin/model-providers/${scopeKey}`,
    method: 'put',
    data: buildProviderPayload(scope)
  });

  if (!error && data && modelProviders.value) {
    modelProviders.value[scopeKey] = cloneModelProviderScope(data);
    window.$message?.success(scopeKey === 'llm' ? 'LLM 供应商配置已更新' : 'Embedding 供应商配置已更新');
  }
  modelProvidersSaving.value = false;
}

async function testModelProvider(scopeKey: 'llm' | 'embedding', provider: Api.Admin.ModelProviderItem) {
  const { error, data } = await request<Api.Admin.ConnectivityTestResult>({
    url: `/admin/model-providers/${scopeKey}/test`,
    method: 'post',
    data: {
      provider: provider.provider,
      apiBaseUrl: provider.apiBaseUrl,
      model: provider.model,
      apiKey: provider.apiKeyInput?.trim() || '',
      dimension: scopeKey === 'embedding' ? provider.dimension : null
    }
  });

  if (!error && data) {
    if (data.success) {
      window.$message?.success(`${provider.displayName} 连接成功，耗时 ${data.latencyMs}ms`);
    } else {
      window.$message?.error(`${provider.displayName} 连接失败：${data.message}`);
    }
  }
}

const rerankLoading = ref(false);
const rerankTesting = ref(false);
const rerankError = ref('');
const rerankStatus = ref<Api.Admin.RerankProviderStatus | null>(null);
const rerankTestResult = ref<Api.Admin.ConnectivityTestResult | null>(null);

const rerankKeySourceLabel = computed(() => {
  switch (rerankStatus.value?.keySource) {
    case 'dedicated':
      return '独立密钥（SEARCH_RERANK_API_KEY）';
    case 'embedding-aliyun':
      return '复用阿里云 Embedding 供应商密钥';
    case 'none':
      return '未配置';
    default:
      return '';
  }
});

async function getRerankStatus() {
  rerankLoading.value = true;
  const { error, data } = await request<Api.Admin.RerankProviderStatus>({
    url: '/admin/model-providers/rerank'
  });

  if (!error && data) {
    rerankStatus.value = data;
    rerankError.value = '';
  } else if (!rerankStatus.value) {
    rerankError.value = '无法获取 Rerank 供应商状态，请检查网络后重试';
  }
  rerankLoading.value = false;
}

async function testRerankConnection() {
  rerankTesting.value = true;
  rerankTestResult.value = null;
  const { error, data } = await request<Api.Admin.ConnectivityTestResult>({
    url: '/admin/model-providers/rerank/test',
    method: 'post'
  });

  if (!error && data) {
    rerankTestResult.value = data;
    if (data.success) {
      window.$message?.success(`Rerank 连接成功，耗时 ${data.latencyMs}ms`);
    } else {
      window.$message?.error(`Rerank 连接失败：${data.message}`);
    }
  }
  rerankTesting.value = false;
}

const failoverLoading = ref(false);
const failoverError = ref('');
const failoverStatus = ref<Api.Admin.LlmFailoverStatus | null>(null);
const failoverTesting = ref<Record<string, boolean>>({});
const failoverTestResults = ref<Record<string, Api.Admin.ConnectivityTestResult | null>>({});

const circuitStateMeta: Record<string, { label: string; type: 'success' | 'warning' | 'error' | 'default' }> = {
  CLOSED: { label: '熔断器正常', type: 'success' },
  OPEN: { label: '已熔断', type: 'error' },
  HALF_OPEN: { label: '半开探测中', type: 'warning' },
  FORCED_OPEN: { label: '强制熔断', type: 'error' },
  DISABLED: { label: '熔断器停用', type: 'default' },
  METRICS_ONLY: { label: '仅统计指标', type: 'default' }
};

function circuitMeta(state: string | null) {
  if (!state) {
    return { label: '熔断状态未知', type: 'default' as const };
  }
  return circuitStateMeta[state] || { label: state, type: 'default' as const };
}

async function getFailoverStatus() {
  failoverLoading.value = true;
  failoverError.value = '';
  const { error, data } = await request<Api.Admin.LlmFailoverStatus>({
    url: '/admin/model-providers/llm/failover'
  });

  if (!error && data) {
    failoverStatus.value = data;
    failoverTestResults.value = {};
  } else if (!failoverStatus.value) {
    failoverError.value = '无法获取 LLM 主备转移链，请检查网络后重试';
  }
  failoverLoading.value = false;
}

async function testFailoverProvider(entry: Api.Admin.FailoverProviderStatus) {
  failoverTesting.value = { ...failoverTesting.value, [entry.provider]: true };
  const { error, data } = await request<Api.Admin.ConnectivityTestResult>({
    url: '/admin/model-providers/llm/test',
    method: 'post',
    data: {
      provider: entry.provider,
      apiBaseUrl: entry.apiBaseUrl,
      model: entry.model,
      apiKey: '',
      dimension: null
    }
  });

  if (!error && data) {
    failoverTestResults.value = { ...failoverTestResults.value, [entry.provider]: data };
    if (data.success) {
      window.$message?.success(`${entry.displayName} 连接成功，耗时 ${data.latencyMs}ms`);
    } else {
      window.$message?.error(`${entry.displayName} 连接失败：${data.message}`);
    }
  }
  failoverTesting.value = { ...failoverTesting.value, [entry.provider]: false };
}

onMounted(() => {
  getModelProviders();
  getRerankStatus();
  getFailoverStatus();
});
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch overflow-auto">
    <h1 class="page-title">模型供应商</h1>
    <NCard title="模型供应商" :bordered="false" size="small" class="page-section sm:flex-1-hidden">
      <div class="settings-content">
        <NTabs v-model:value="selectedScope" type="line" size="large" class="scope-tabs">
          <NTab name="llm">聊天模型</NTab>
          <NTab name="embedding">Embedding</NTab>
          <NTab name="rerank">Rerank 精排</NTab>
        </NTabs>

        <NSpin :show="modelProvidersLoading">
          <div v-if="selectedScope === 'rerank'" class="settings-layout rerank-layout">
            <aside class="provider-navigation">
              <div class="navigation-label">精排供应商</div>
              <div class="rerank-summary">
                <p class="rerank-summary__title">{{ rerankStatus?.model || '—' }}</p>
                <NTag :bordered="false" size="small" :type="rerankStatus?.enabled ? 'success' : 'default'">
                  {{ rerankStatus?.enabled ? '已启用' : '已关闭' }}
                </NTag>
              </div>
              <p class="rerank-side-note">
                关闭或调用失败时，检索按 RRF 融合顺序直接截取 topK，问答不中断，但相关性精排不生效。
              </p>
            </aside>
            <section class="provider-editor" aria-label="Rerank 精排配置">
              <header class="editor-heading">
                <h2>Rerank 精排</h2>
              </header>
              <div v-if="rerankError && !rerankStatus" class="rerank-state">
                <p class="rerank-state__desc">{{ rerankError }}</p>
                <NButton size="small" :disabled="rerankLoading" @click="getRerankStatus">
                  <template #icon>
                    <icon-lucide:rotate-cw />
                  </template>
                  重试
                </NButton>
              </div>
              <div v-else-if="rerankStatus" class="provider-fields">
                <div class="field">
                  <label for="rerank-api-url">API 地址</label>
                  <p id="rerank-api-url" class="readonly-value">{{ rerankStatus.url }}</p>
                </div>
                <div class="field">
                  <label for="rerank-model">模型</label>
                  <p id="rerank-model" class="readonly-value">{{ rerankStatus.model }}</p>
                </div>
                <div class="field">
                  <div class="key-label">
                    <label for="rerank-key">API Key</label>
                    <span class="saved-key" :class="{ 'is-empty': !rerankStatus.hasApiKey }">
                      {{ rerankStatus.hasApiKey ? rerankStatus.maskedApiKey : '未配置' }}
                    </span>
                  </div>
                  <p id="rerank-key" class="readonly-value">密钥来源：{{ rerankKeySourceLabel }}</p>
                </div>
                <div class="field">
                  <label for="rerank-params">运行参数</label>
                  <p id="rerank-params" class="readonly-value">
                    超时 {{ rerankStatus.timeoutSeconds }}s · 分数阈值 {{ rerankStatus.scoreThreshold }} · 候选上限
                    {{ rerankStatus.maxCandidates }}
                  </p>
                </div>
              </div>
              <div v-else class="rerank-state">
                <p class="rerank-state__desc">正在加载 Rerank 供应商状态…</p>
              </div>
              <footer class="editor-actions">
                <NButton quaternary :loading="rerankTesting" :disabled="!rerankStatus" @click="testRerankConnection">
                  测试连接
                </NButton>
              </footer>
              <p class="rerank-note">
                Rerank
                供应商由部署环境变量（SEARCH_RERANK_*）驱动，此页为只读状态；如需更换供应商或模型，请更新部署配置后重启服务。
              </p>
              <div
                v-if="rerankTestResult"
                class="test-result"
                :class="rerankTestResult.success ? 'is-success' : 'is-error'"
                role="status"
              >
                <span class="test-result__label">{{ rerankTestResult.success ? '连接成功' : '连接失败' }}</span>
                <span class="test-result__detail">
                  {{ rerankTestResult.success ? `耗时 ${rerankTestResult.latencyMs}ms` : rerankTestResult.message }}
                </span>
              </div>
            </section>
          </div>
          <div v-else-if="modelProvidersError && !currentScope" class="provider-state provider-state--error">
            <div class="app-state-badge app-state-badge--error">
              <icon-lucide:circle-alert class="text-icon-large" />
            </div>
            <p class="provider-state__title">模型供应商配置加载失败</p>
            <p class="provider-state__desc">{{ modelProvidersError }}</p>
            <NButton size="small" :disabled="modelProvidersLoading" @click="getModelProviders">
              <template #icon>
                <icon-lucide:rotate-cw />
              </template>
              重试
            </NButton>
          </div>
          <div v-else-if="currentScope && currentProvider" class="settings-layout">
            <aside class="provider-navigation">
              <div class="navigation-label">供应商</div>
              <nav aria-label="模型供应商" class="provider-list">
                <button
                  v-for="item in currentScope.providers"
                  :key="`${providerScope}-${item.provider}`"
                  type="button"
                  class="provider-option"
                  :class="{ 'is-selected': currentProvider.provider === item.provider }"
                  :aria-current="currentProvider.provider === item.provider ? 'true' : undefined"
                  @click="selectedProviders[providerScope] = item.provider"
                >
                  <span>{{ item.displayName }}</span>
                  <span v-if="item.provider === currentScope.activeProvider" class="active-dot" aria-label="当前使用" />
                </button>
              </nav>
              <div class="active-provider">
                <label class="navigation-label" for="active-provider">当前使用</label>
                <NSelect
                  v-model:value="currentScope.activeProvider"
                  :options="providerOptions"
                  :input-props="{ id: 'active-provider', 'aria-label': '当前使用的供应商' }"
                />
              </div>
            </aside>

            <section class="provider-editor" :aria-label="`${currentProvider.displayName} 配置`">
              <header class="editor-heading">
                <h2>{{ currentProvider.displayName }}</h2>
                <div class="enable-control">
                  <span>{{ currentProvider.enabled ? '已启用' : '未启用' }}</span>
                  <NSwitch v-model:value="currentProvider.enabled" size="small" aria-label="启用供应商" />
                </div>
              </header>

              <div class="provider-fields">
                <div class="field">
                  <label for="provider-api-url">API 地址</label>
                  <NInput
                    v-model:value="currentProvider.apiBaseUrl"
                    size="large"
                    :input-props="{ id: 'provider-api-url' }"
                  />
                </div>
                <div class="model-fields" :class="{ 'has-dimension': selectedScope === 'embedding' }">
                  <div class="field">
                    <label for="provider-model">模型</label>
                    <NInput
                      v-model:value="currentProvider.model"
                      size="large"
                      :input-props="{ id: 'provider-model' }"
                    />
                  </div>
                  <div v-if="selectedScope === 'embedding'" class="field">
                    <label for="provider-dimension">维度</label>
                    <NInputNumber
                      v-model:value="currentProvider.dimension"
                      :min="1"
                      size="large"
                      :input-props="{ id: 'provider-dimension' }"
                    />
                  </div>
                </div>
                <div class="field">
                  <div class="key-label">
                    <label for="provider-key">API Key</label>
                    <span class="saved-key" :class="{ 'is-empty': !currentProvider.hasApiKey }">
                      {{ currentProvider.hasApiKey ? currentProvider.maskedApiKey : '未配置' }}
                    </span>
                  </div>
                  <NInput
                    v-model:value="currentProvider.apiKeyInput"
                    type="password"
                    size="large"
                    show-password-on="click"
                    placeholder="留空则保留现有值"
                    :input-props="{ id: 'provider-key', autocomplete: 'new-password' }"
                  />
                </div>
              </div>

              <footer class="editor-actions">
                <NButton quaternary @click="testModelProvider(providerScope, currentProvider)">测试连接</NButton>
                <NButton type="primary" :loading="modelProvidersSaving" @click="submitModelProviders(providerScope)">
                  保存配置
                </NButton>
              </footer>
            </section>
          </div>
          <div v-else-if="!modelProvidersLoading" class="provider-state">
            <div class="app-state-badge">
              <icon-lucide:cloud-cog class="text-icon-large" />
            </div>
            <p class="provider-state__title">暂未加载到模型供应商配置</p>
            <p class="provider-state__desc">服务返回为空，重试或检查管理员权限后再试</p>
            <NButton size="small" @click="getModelProviders">
              <template #icon>
                <icon-lucide:rotate-cw />
              </template>
              重新加载
            </NButton>
          </div>
        </NSpin>

        <section class="failover-section" aria-label="LLM 主备与转移">
          <header class="failover-heading">
            <div class="failover-heading__text">
              <h2>主备与转移</h2>
              <p class="failover-desc">
                LLM
                问答按以下顺序调用：主供应商失败（熔断打开、并发占满、限流排队超时或重试耗尽）且尚未输出内容时，自动切换到下一家备用供应商；已开始输出后不再切换。备用顺序取自
                model-call.failover.llm-backup-providers{{
                  failoverStatus?.configuredBackupProviders?.length
                    ? '（已显式配置）'
                    : '（未配置，按供应商编码字母序生效）'
                }}，仅包含已启用且已配置密钥的供应商。
              </p>
            </div>
            <div class="failover-actions">
              <NTag :bordered="false" size="small" :type="failoverStatus?.failoverEnabled ? 'success' : 'warning'">
                {{ failoverStatus?.failoverEnabled ? '故障转移已开启' : '故障转移已关闭' }}
              </NTag>
              <NButton size="small" quaternary :loading="failoverLoading" @click="getFailoverStatus">
                <template #icon>
                  <icon-lucide:rotate-cw />
                </template>
                刷新
              </NButton>
            </div>
          </header>

          <div v-if="failoverError && !failoverStatus" class="rerank-state">
            <p class="rerank-state__desc">{{ failoverError }}</p>
            <NButton size="small" :disabled="failoverLoading" @click="getFailoverStatus">
              <template #icon>
                <icon-lucide:rotate-cw />
              </template>
              重试
            </NButton>
          </div>
          <ol v-else-if="failoverStatus" class="failover-list">
            <li
              v-for="entry in failoverStatus.providers"
              :key="`failover-${entry.provider}`"
              class="failover-item"
              :class="{ 'is-excluded': !entry.inChain }"
            >
              <span class="failover-order" :class="{ 'is-primary': entry.primary }">
                {{ entry.primary ? '主' : entry.inChain ? `备${entry.position - 1}` : '—' }}
              </span>
              <div class="failover-info">
                <div class="failover-name">
                  <span>{{ entry.displayName }}</span>
                  <code>{{ entry.provider }} / {{ entry.model }}</code>
                </div>
                <div class="failover-status">
                  <NTag
                    v-if="entry.inChain"
                    :bordered="false"
                    size="small"
                    :type="circuitMeta(entry.circuitState).type"
                  >
                    {{ circuitMeta(entry.circuitState).label }}
                  </NTag>
                  <NTag v-if="!entry.inChain" :bordered="false" size="small" type="default">
                    {{ entry.excludedReason || '未参与调用链' }}
                  </NTag>
                  <span v-if="entry.primary" class="failover-hint">主供应商优先承载全部问答</span>
                </div>
              </div>
              <div class="failover-test">
                <NButton
                  size="small"
                  quaternary
                  :loading="Boolean(failoverTesting[entry.provider])"
                  @click="testFailoverProvider(entry)"
                >
                  连通性测试
                </NButton>
                <span
                  v-if="failoverTestResults[entry.provider]"
                  class="test-result test-result--inline"
                  :class="failoverTestResults[entry.provider]?.success ? 'is-success' : 'is-error'"
                  role="status"
                >
                  {{
                    failoverTestResults[entry.provider]?.success
                      ? `成功 · ${failoverTestResults[entry.provider]?.latencyMs}ms`
                      : `失败：${failoverTestResults[entry.provider]?.message}`
                  }}
                </span>
              </div>
            </li>
          </ol>
        </section>
      </div>
    </NCard>
  </div>
</template>

<style scoped lang="scss">
.settings-content {
  max-width: 1100px;
  width: 100%;
  margin: 0 auto;
  padding-top: 8px;
}

.settings-layout {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: 48px;
  padding-top: 32px;
}

.navigation-label {
  display: block;
  margin-bottom: 12px;
  font-size: 14px;
  color: var(--app-text-secondary);
}

.provider-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.provider-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 12px 14px;
  border: 0;
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-text-secondary);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition:
    background var(--app-dur-fast) var(--app-ease-standard),
    color var(--app-dur-fast) var(--app-ease-standard);

  &:hover {
    background: var(--app-fill-hover);
  }

  /* Selected nav item: fill-subtle base + primary text; the indigo stays on the active dot only */
  &.is-selected {
    background: var(--app-fill-subtle);
    color: var(--app-text);
    font-weight: 500;
  }

  &:focus-visible {
    outline: 2px solid rgb(var(--primary-color));
    outline-offset: 2px;
  }
}

.active-dot {
  width: 6px;
  height: 6px;
  flex-shrink: 0;
  border-radius: 50%;
  background: rgb(var(--primary-color));
}

.active-provider {
  margin-top: 32px;
}

.editor-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 32px;

  h2 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    line-height: 1.35;
    color: var(--app-text);
  }
}

.enable-control {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.provider-fields {
  display: grid;
  gap: 24px;
}

.field {
  min-width: 0;

  label {
    display: block;
    margin-bottom: 10px;
    font-size: 14px;
    font-weight: 500;
    color: var(--app-text-secondary);
  }
}

.model-fields {
  display: grid;
  gap: 20px;

  &.has-dimension {
    grid-template-columns: minmax(0, 1fr) 160px;
  }
}

.key-label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.saved-key {
  color: var(--app-text-secondary);
  font-family: ui-monospace, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace;
  font-size: 14px;
  font-variant-numeric: tabular-nums;

  &.is-empty {
    color: var(--app-text-tertiary);
  }
}

.editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 32px;
}

/* —— pm-028：Rerank 只读状态卡 —— */
.rerank-layout {
  align-items: start;
}

.rerank-summary {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border-radius: var(--app-radius-sm);
  background: var(--app-fill-subtle);

  &__title {
    margin: 0;
    font-size: 14px;
    font-weight: 500;
    color: var(--app-text);
    font-family: ui-monospace, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace;
  }
}

.rerank-side-note {
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-muted);
}

.rerank-state {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
  padding: 24px 0;

  &__desc {
    margin: 0;
    font-size: 14px;
    color: var(--app-muted);
  }
}

.readonly-value {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--app-radius-sm);
  background: var(--app-fill-subtle);
  font-size: 14px;
  line-height: 1.5;
  color: var(--app-text);
  font-family: ui-monospace, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace;
  overflow-wrap: anywhere;
}

.rerank-key-source {
  font-family: inherit;
  color: var(--app-text-secondary);
}

.rerank-note {
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-muted);
}

/* —— pm-028：连通性测试结果（成功/失败 + 耗时，就地反馈）—— */
.test-result {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 16px;
  padding: 10px 12px;
  border-radius: var(--app-radius-sm);
  font-size: 13px;
  line-height: 1.5;

  &__label {
    flex-shrink: 0;
    font-weight: 600;
  }

  &__detail {
    color: var(--app-text-secondary);
    overflow-wrap: anywhere;
  }

  &--inline {
    margin-top: 0;
    padding: 4px 8px;
    font-size: 12px;
  }

  &.is-success {
    background: rgb(var(--success-color) / 0.1);

    .test-result__label {
      color: rgb(var(--success-color));
    }
  }

  &.is-error {
    background: rgb(var(--error-color) / 0.1);

    .test-result__label {
      color: rgb(var(--error-color));
    }
  }
}

/* —— pm-028：主备与转移区块 —— */
.failover-section {
  margin-top: 40px;
  padding-top: 32px;
  border-top: 1px solid rgba(127, 127, 127, 0.16);
}

.failover-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;

  h2 {
    margin: 0 0 8px;
    font-size: 16px;
    font-weight: 600;
    line-height: 1.35;
    color: var(--app-text);
  }

  &__text {
    max-width: 640px;
  }
}

.failover-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.failover-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--app-muted);
}

.failover-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.failover-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 14px;
  border-radius: var(--app-radius-sm);
  background: var(--app-fill-subtle);

  &.is-excluded {
    opacity: 0.68;
  }
}

.failover-order {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--app-fill-hover);
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-secondary);
  font-variant-numeric: tabular-nums;

  &.is-primary {
    background: rgb(var(--primary-color));
    color: #fff;
  }
}

.failover-info {
  flex: 1;
  min-width: 0;
}

.failover-name {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 14px;
  font-weight: 500;
  color: var(--app-text);

  code {
    font-size: 12px;
    font-weight: 400;
    color: var(--app-text-secondary);
    font-family: ui-monospace, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace;
  }
}

.failover-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.failover-hint {
  font-size: 12px;
  color: var(--app-muted);
}

.failover-test {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  max-width: 340px;
}

@media (max-width: 640px) {
  .failover-heading {
    flex-direction: column;
    gap: 12px;
  }

  .failover-item {
    flex-wrap: wrap;
  }

  .failover-test {
    width: 100%;
    max-width: none;
    justify-content: flex-end;
  }
}

.provider-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 320px;
  padding: 48px 24px;
  text-align: center;
  animation: provider-state-in var(--app-dur-base) var(--app-ease-standard);
}

/* R11 delight：空态图标改吃 global.scss 的 azure 徽标 tile（.app-state-badge，error 变体
   走语义红 wash）——裸灰 ghost 图标就此从空态退役，__icon 规则随之删除 */
.provider-state__title {
  margin: 8px 0 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--app-text);
}

.provider-state__desc {
  margin: 0;
  max-width: 360px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--app-muted);
}

@keyframes provider-state-in {
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
  .settings-layout {
    grid-template-columns: 160px minmax(0, 1fr);
    gap: 28px;
  }
}

@media (max-width: 640px) {
  .settings-content {
    padding-top: 0;
  }

  .settings-layout {
    grid-template-columns: minmax(0, 1fr);
    gap: 32px;
    padding-top: 24px;
  }

  .provider-list {
    flex-direction: row;
    flex-wrap: wrap;
  }

  .provider-option {
    width: auto;
    gap: 12px;
  }

  .active-provider {
    margin-top: 20px;
    max-width: 240px;
  }

  .model-fields.has-dimension {
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
