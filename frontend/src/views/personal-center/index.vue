<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import { NTag } from 'naive-ui';
const { userInfo } = storeToRefs(useAuthStore());

const tags = ref<Api.OrgTag.Mine>({
  orgTags: [],
  primaryOrg: '',
  orgTagDetails: []
});

const usage = ref<Api.User.UsageSnapshot>({
  day: '',
  chatRequestCount: 0,
  llm: {
    enabled: false,
    usedTokens: 0,
    limitTokens: 0,
    remainingTokens: 0,
    requestCount: 0
  },
  embedding: {
    enabled: false,
    usedTokens: 0,
    limitTokens: 0,
    remainingTokens: 0,
    requestCount: 0
  }
});

const loading = ref(false);
const usageLoadError = ref(false);
const orgTagsError = ref(false);

// Token 记录相关变量
const tokenRecords = ref<Api.User.TokenRecord[]>([]);
const tokenRecordLoading = ref(false);
const tokenRecordError = ref(false);
const pagination = ref({
  page: 1,
  pageSize: 10,
  total: 0,
  pageCount: 0
});
const getTokenRecords = async () => {
  tokenRecordLoading.value = true;
  try {
    const { error, data } = await request({
      url: '/users/token-records',
      method: 'GET',
      params: {
        page: pagination.value.page - 1,
        size: pagination.value.pageSize
      }
    });

    if (!error && data) {
      tokenRecords.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
      pagination.value.pageCount = data.totalPages || 0;
      tokenRecordError.value = false;
    } else {
      tokenRecordError.value = true;
    }
  } finally {
    tokenRecordLoading.value = false;
  }
};

const getPersonalData = async () => {
  loading.value = true;
  const [{ error: orgError, data: orgData }, { error: usageError, data: usageData }] = await Promise.all([
    request<Api.OrgTag.Mine>({
      url: '/users/org-tags'
    }),
    request<Api.User.UsageSnapshot>({
      url: '/users/usage'
    })
  ]);

  if (!orgError) {
    tags.value = orgData;
  }
  orgTagsError.value = Boolean(orgError);

  if (!usageError) {
    usage.value = usageData;
    usageLoadError.value = false;
  } else {
    usageLoadError.value = true;
  }

  // 获取 Token 记录
  getTokenRecords();

  loading.value = false;
};

const getOrgTags = async () => {
  const { error, data } = await request<Api.OrgTag.Mine>({
    url: '/users/org-tags'
  });
  if (!error) {
    tags.value = data;
  }
};

onMounted(() => {
  getPersonalData();
});

const visible = ref(false);
const currentTagId = ref('');
const showModal = (tagId: string) => {
  if (tagId === tags.value.primaryOrg) return;
  visible.value = true;
  currentTagId.value = tagId;
};
const submitLoading = ref(false);
const setPrimaryOrg = async () => {
  submitLoading.value = true;
  const { error } = await request({
    url: '/users/primary-org',
    method: 'PUT',
    data: { primaryOrg: currentTagId.value, userId: userInfo.value.id }
  });
  if (!error) {
    visible.value = false;
    getOrgTags();
  }
  submitLoading.value = false;
};

const handlePageChange = (page: number) => {
  pagination.value.page = page;
  getTokenRecords();
};

// Token 记录表格列定义
const tokenRecordColumns = computed(() => [
  {
    title: '日期',
    key: 'recordDate',
    width: 130,
    render: (row: Api.User.TokenRecord) => row.recordDate
  },
  {
    title: 'Token 类型',
    key: 'tokenType',
    width: 140,
    render: (row: Api.User.TokenRecord) => {
      const typeMap: Record<string, { text: string; type: any }> = {
        LLM: { text: 'LLM', type: 'info' },
        EMBEDDING: { text: 'Embedding', type: 'success' }
      };
      const type = typeMap[row.tokenType] || { text: row.tokenType, type: 'default' };
      return h(NTag, { type: type.type, bordered: false }, () => type.text);
    }
  },
  {
    title: '变动类型',
    key: 'changeType',
    width: 100,
    render: (row: Api.User.TokenRecord) => {
      const typeMap: Record<string, { text: string; type: any }> = {
        INCREASE: { text: '充值', type: 'success' },
        CONSUME: { text: '消耗', type: 'warning' }
      };
      const type = typeMap[row.changeType] || { text: row.changeType, type: 'default' };
      return h(NTag, { type: type.type, bordered: false }, () => type.text);
    }
  },
  {
    title: '变动数量',
    key: 'amount',
    width: 120,
    render: (row: Api.User.TokenRecord) => {
      const sign = row.changeType === 'INCREASE' ? '+' : '-';
      return `${sign}${row.amount.toLocaleString()}`;
    }
  },
  {
    title: '变动前余额',
    key: 'balanceBefore',
    width: 120,
    render: (row: Api.User.TokenRecord) => row.balanceBefore?.toLocaleString() || '-'
  },
  {
    title: '变动后余额',
    key: 'balanceAfter',
    width: 120,
    render: (row: Api.User.TokenRecord) => row.balanceAfter?.toLocaleString() || '-'
  },
  {
    title: '原因',
    key: 'reason',
    minWidth: 100,
    ellipsis: { tooltip: true },
    render: (row: Api.User.TokenRecord) => row.reason || '-'
  },
  {
    title: '请求次数',
    key: 'requestCount',
    width: 80,
    render: (row: Api.User.TokenRecord) => row.requestCount?.toLocaleString() || '0'
  },
  {
    title: '创建时间',
    key: 'createdAt',
    width: 180,
    render: (row: Api.User.TokenRecord) => new Date(row.createdAt).toLocaleString('zh-CN')
  }
]);
</script>

<template>
  <div class="workspace-page profile-page overflow-auto">
    <h1 class="page-title">个人中心</h1>
    <NSpin :show="loading">
      <NCard :bordered="false" class="page-section profile-section">
        <template #header>
          <div class="flex items-center gap-4">
            <div class="profile-avatar">
              <icon-lucide:circle-user-round class="text-icon-large" />
            </div>
            <div>
              <div>{{ userInfo.username }}</div>
              <div class="text-muted mt-1 text-xs">今日额度 · {{ usage.day || '未统计' }}</div>
            </div>
          </div>
        </template>

        <div v-if="usageLoadError" class="page-state">
          <icon-lucide:circle-alert class="page-state__icon page-state__icon--error" />
          <p class="page-state__title">今日额度加载失败</p>
          <p class="page-state__desc">网络异常导致用量数据没有加载，请检查连接后重试</p>
          <NButton size="small" :disabled="loading" @click="getPersonalData">
            <template #icon>
              <icon-lucide:rotate-cw />
            </template>
            重试
          </NButton>
        </div>
        <div v-else class="quota-grid">
          <section
            v-for="(quota, scope) in { llm: usage.llm, embedding: usage.embedding }"
            :key="scope"
            class="quota-block"
          >
            <h2>{{ scope === 'llm' ? 'LLM Token' : 'Embedding Token' }}</h2>
            <template v-if="quota.enabled">
              <div class="quota-value">
                {{ quota.remainingTokens.toLocaleString() }}
                <span>剩余</span>
              </div>
              <NProgress
                type="line"
                :percentage="
                  Math.min(100, Math.max(0, quota.limitTokens > 0 ? (quota.usedTokens / quota.limitTokens) * 100 : 0))
                "
                :show-indicator="false"
                :height="4"
              />
              <div class="text-muted quota-meta mt-3 flex flex-wrap justify-between gap-2 text-xs">
                <span>已用 {{ quota.usedTokens.toLocaleString() }} / {{ quota.limitTokens.toLocaleString() }}</span>
                <span>请求 {{ quota.requestCount.toLocaleString() }} 次</span>
              </div>
            </template>
            <div v-else class="text-muted mt-4">当前未启用配额</div>
          </section>
        </div>

        <section class="profile-tags">
          <h2>组织标签</h2>
          <div v-if="tags.orgTagDetails.length > 0" class="org-tag-grid">
            <button
              v-for="tag in tags.orgTagDetails"
              :key="tag.tagId"
              type="button"
              class="org-tag-option"
              :class="{ 'is-primary': tag.tagId === tags.primaryOrg }"
              @click="showModal(tag.tagId)"
            >
              <div class="flex items-center justify-between gap-3">
                <span>{{ tag.name }}</span>
                <NTag v-if="tag.tagId === tags.primaryOrg" type="primary" size="small" :bordered="false">主标签</NTag>
              </div>
              <p class="text-muted mt-2 text-xs">{{ tag.description }}</p>
            </button>
          </div>
          <p v-else-if="orgTagsError" class="text-muted profile-note">组织标签加载失败，请刷新页面重试</p>
          <p v-else class="profile-note text-muted">暂无组织标签，请联系管理员加入组织</p>
        </section>

        <section class="profile-records">
          <h2>Token 变动记录</h2>
          <NSpin :show="tokenRecordLoading">
            <div v-if="tokenRecordError" class="page-state">
              <icon-lucide:circle-alert class="page-state__icon page-state__icon--error" />
              <p class="page-state__title">Token 记录加载失败</p>
              <p class="page-state__desc">网络异常导致记录没有加载，请检查连接后重试</p>
              <NButton size="small" :disabled="tokenRecordLoading" @click="getTokenRecords">
                <template #icon>
                  <icon-lucide:rotate-cw />
                </template>
                重试
              </NButton>
            </div>
            <NDataTable
              v-else-if="tokenRecords.length > 0"
              :bordered="false"
              :columns="tokenRecordColumns"
              :data="tokenRecords"
              :loading="tokenRecordLoading"
              :pagination="{
                page: pagination.page,
                pageSize: pagination.pageSize,
                itemCount: pagination.total,
                onChange: handlePageChange
              }"
              :scroll-x="1200"
              size="small"
            />
            <div v-else class="page-state">
              <icon-lucide:receipt-text class="page-state__icon" />
              <p class="page-state__title">暂无 Token 变动记录</p>
              <p class="page-state__desc">充值或消耗 Token 之后，每笔记录会汇总到这里</p>
            </div>
          </NSpin>
        </section>
      </NCard>
    </NSpin>
    <NModal
      v-model:show="visible"
      :loading="submitLoading"
      preset="dialog"
      title="设置主标签"
      content="确定将当前标签设置为主标签吗？"
      positive-text="确认"
      negative-text="取消"
      @positive-click="setPrimaryOrg"
      @negative-click="visible = false"
    />
  </div>
</template>

<style scoped lang="scss">
.profile-section {
  max-width: 1120px;
  margin: 0 auto;
}
.profile-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  border-radius: 9999px;
  background: var(--app-fill-subtle);
  color: var(--app-text-secondary);
}
.quota-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 48px;
  padding-top: 8px;
}
h2 {
  margin: 0 0 16px;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.4;
}
.quota-value {
  margin: 16px 0;
  font-size: 28px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.quota-value span {
  margin-left: 12px;
  font-size: 12px;
  font-weight: 400;
  color: var(--app-muted);
}
.quota-meta {
  font-variant-numeric: tabular-nums;
}
.profile-tags,
.profile-records {
  margin-top: 32px;
  border-top: 1px solid var(--app-border);
  padding-top: 24px;
}
.org-tag-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
}
.profile-note {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
}
.org-tag-option {
  padding: 16px;
  border: 1px solid transparent;
  border-radius: var(--app-radius-sm);
  text-align: left;
  background: var(--app-fill-subtle);
  color: var(--app-text);
  cursor: pointer;
  transition:
    background-color var(--app-dur-fast) var(--app-ease-standard),
    border-color var(--app-dur-fast) var(--app-ease-standard);
}
.org-tag-option:hover {
  background: var(--app-fill-hover);
}
.org-tag-option:focus-visible {
  outline: 2px solid rgb(var(--primary-color));
  outline-offset: 2px;
}
.org-tag-option.is-primary {
  cursor: default;
}
.org-tag-option.is-primary:hover {
  background: var(--app-fill-subtle);
}
.page-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 240px;
  padding: 48px 24px;
  text-align: center;
  animation: page-state-in var(--app-dur-base) var(--app-ease-standard);
}
.page-state__icon {
  font-size: 40px;
  color: rgb(var(--base-text-color) / 0.24);
}
.page-state__icon--error {
  color: rgb(var(--error-700-color));
}
.page-state__title {
  margin: 8px 0 0;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.25;
  color: var(--app-text);
}
.page-state__desc {
  margin: 0;
  max-width: 360px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--app-muted);
}
html.dark .page-state__icon--error {
  color: rgb(var(--error-400-color));
}
@keyframes page-state-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@media (max-width: 640px) {
  .quota-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 32px;
  }
  .quota-value {
    font-size: 24px;
  }
  .org-tag-grid {
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
