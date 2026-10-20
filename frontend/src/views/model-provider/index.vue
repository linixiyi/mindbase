<script setup lang="tsx">
import { computed, onMounted, ref } from 'vue';
import { NButton } from 'naive-ui';

const modelProvidersLoading = ref(false);
const modelProvidersSaving = ref(false);
const modelProvidersError = ref('');
const modelProviders = ref<Api.Admin.ModelProviderSettings | null>(null);
const selectedScope = ref<'llm' | 'embedding'>('llm');
const selectedProviders = ref({ llm: '', embedding: '' });
const currentScope = computed(() => modelProviders.value?.[selectedScope.value]);
const currentProvider = computed(() => {
  const scope = currentScope.value;
  return (
    scope?.providers.find(item => item.provider === selectedProviders.value[selectedScope.value]) ||
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
    modelProvidersError.value = '无法获取模型配置，请检查网络后重试';
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
    window.$message?.success(scopeKey === 'llm' ? 'LLM 模型配置已更新' : 'Embedding 配置已更新');
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

onMounted(() => {
  getModelProviders();
});
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch overflow-auto">
    <NCard title="模型配置" :bordered="false" size="small" class="page-section sm:flex-1-hidden">
      <div class="settings-content">
        <NTabs v-model:value="selectedScope" type="line" size="large" class="scope-tabs">
          <NTab name="llm">聊天模型</NTab>
          <NTab name="embedding">Embedding</NTab>
        </NTabs>

        <NSpin :show="modelProvidersLoading">
          <div v-if="modelProvidersError && !currentScope" class="provider-state provider-state--error">
            <icon-material-symbols:error-outline-rounded class="provider-state__icon" />
            <p class="provider-state__title">模型配置加载失败</p>
            <p class="provider-state__desc">{{ modelProvidersError }}</p>
            <NButton size="small" :disabled="modelProvidersLoading" @click="getModelProviders">
              <template #icon>
                <icon-mdi:refresh />
              </template>
              重试
            </NButton>
          </div>
          <div v-else-if="currentScope && currentProvider" class="settings-layout">
            <aside class="provider-navigation">
              <div class="navigation-label">服务商</div>
              <nav aria-label="模型服务商" class="provider-list">
                <button
                  v-for="item in currentScope.providers"
                  :key="`${selectedScope}-${item.provider}`"
                  type="button"
                  class="provider-option"
                  :class="{ 'is-selected': currentProvider.provider === item.provider }"
                  :aria-current="currentProvider.provider === item.provider ? 'true' : undefined"
                  @click="selectedProviders[selectedScope] = item.provider"
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
                  :input-props="{ id: 'active-provider', 'aria-label': '当前使用的服务商' }"
                />
              </div>
            </aside>

            <section class="provider-editor" :aria-label="`${currentProvider.displayName} 配置`">
              <header class="editor-heading">
                <h2>{{ currentProvider.displayName }}</h2>
                <div class="enable-control">
                  <span>{{ currentProvider.enabled ? '已启用' : '未启用' }}</span>
                  <NSwitch v-model:value="currentProvider.enabled" size="small" aria-label="启用服务商" />
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
                <NButton quaternary @click="testModelProvider(selectedScope, currentProvider)">测试连接</NButton>
                <NButton type="primary" :loading="modelProvidersSaving" @click="submitModelProviders(selectedScope)">
                  保存{{ selectedScope === 'llm' ? '聊天模型' : 'Embedding' }}配置
                </NButton>
              </footer>
            </section>
          </div>
          <div v-else-if="!modelProvidersLoading" class="provider-state">
            <icon-mdi:cloud-cog-outline class="provider-state__icon" />
            <p class="provider-state__title">暂未加载到模型配置</p>
            <p class="provider-state__desc">服务返回为空，重试或检查管理员权限后再试</p>
            <NButton size="small" @click="getModelProviders">
              <template #icon>
                <icon-mdi:refresh />
              </template>
              重新加载
            </NButton>
          </div>
        </NSpin>
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
  font-size: 13px;
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
    font-size: 17px;
    font-weight: 600;
    line-height: 1.4;
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
    font-size: 13px;
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
  font-size: 13px;
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

.provider-state__icon {
  font-size: 40px;
  color: rgb(var(--base-text-color) / 0.24);
}

.provider-state__title {
  margin: 8px 0 0;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--app-text);
}

.provider-state__desc {
  margin: 0;
  max-width: 360px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--app-muted);
}

.provider-state--error .provider-state__icon {
  color: rgb(var(--error-700-color));
}

html.dark .provider-state--error .provider-state__icon {
  color: rgb(var(--error-400-color));
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
</style>
