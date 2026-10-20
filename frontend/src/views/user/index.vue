<script setup lang="tsx">
import { ref } from 'vue';
import { NButton, NTag } from 'naive-ui';
import UserSearch from './modules/user-search.vue';
import OrgTagSettingDialog from './modules/org-tag-setting-dialog.vue';
import TokenQuotaDialog from './modules/token-quota-dialog.vue';

const appStore = useAppStore();
const authStore = useAuthStore();

function apiFn(params: Api.User.SearchParams) {
  return request<Api.User.List>({ url: '/admin/users/list', params });
}

const {
  columns,
  columnChecks,
  data,
  getData,
  loading,
  mobilePagination,
  updateSearchParams,
  getDataByPage,
  searchParams
} = useTable({
  apiFn,
  apiParams: {
    keyword: null,
    orgTag: null,
    status: null
  },
  columns: () => [
    {
      key: 'index',
      title: '序号',
      width: 64
    },
    {
      key: 'username',
      title: '用户名',
      minWidth: 150,
      ellipsis: { tooltip: true }
    },
    {
      key: 'orgTags',
      title: '标签',
      render: row => (
        <div class="flex flex-wrap gap-2">
          {row.orgTags.map(tag => (
            <NTag bordered={false} key={tag.tagId} type={tag.tagId === row.primaryOrg ? 'primary' : 'default'}>
              {tag.name}
            </NTag>
          ))}
        </div>
      )
    },
    {
      key: 'status',
      title: '是否启用',
      width: 100,
      render: row => (
        <NTag bordered={false} type={row.status ? 'success' : 'warning'}>
          {row.status ? '已启用' : '已禁用'}
        </NTag>
      )
    },
    {
      key: 'createdAt',
      title: '创建时间',
      width: 200,
      render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss')
    },
    {
      key: 'chatUsage',
      title: '聊天次数',
      width: 130,
      render: row => (
        <div class="flex flex-col gap-1 text-xs">
          <span>{Number(row.usage?.chatRequestCount || 0).toLocaleString()} 次</span>
          <span class="text-muted">今日消息数</span>
        </div>
      )
    },
    {
      key: 'llmUsage',
      title: 'LLM额度',
      width: 220,
      render: row => {
        const quota = row.usage?.llm;
        if (!quota?.enabled) {
          return <span class="text-muted">未启用</span>;
        }
        return (
          <div class="flex flex-col gap-1 text-xs">
            <span>
              {Number(quota.usedTokens || 0).toLocaleString()} / {Number(quota.limitTokens || 0).toLocaleString()}
            </span>
            <span class="text-muted">
              剩余 {Number(quota.remainingTokens || 0).toLocaleString()} · {quota.requestCount} 次
            </span>
          </div>
        );
      }
    },
    {
      key: 'embeddingUsage',
      title: 'Embedding额度',
      width: 220,
      render: row => {
        const quota = row.usage?.embedding;
        if (!quota?.enabled) {
          return <span class="text-muted">未启用</span>;
        }
        return (
          <div class="flex flex-col gap-1 text-xs">
            <span>
              {Number(quota.usedTokens || 0).toLocaleString()} / {Number(quota.limitTokens || 0).toLocaleString()}
            </span>
            <span class="text-muted">
              剩余 {Number(quota.remainingTokens || 0).toLocaleString()} · {quota.requestCount} 次
            </span>
          </div>
        );
      }
    },
    {
      key: 'operate',
      title: '操作',
      width: 230,
      render: row => (
        <div class="flex gap-2">
          <NButton type="primary" quaternary size="small" onClick={() => handleOrgTag(row)}>
            分配组织标签
          </NButton>
          {authStore.isAdmin ? (
            <NButton quaternary size="small" onClick={() => handleTokenQuota(row)}>
              追加 Token
            </NButton>
          ) : null}
        </div>
      )
    }
  ]
});

const visible = ref(false);
const editingData = ref<Api.User.Item | null>(null);
const tokenVisible = ref(false);
const tokenEditingData = ref<Api.User.Item | null>(null);

const hasActiveFilters = computed(() => Boolean(searchParams.keyword || searchParams.orgTag || searchParams.status));

function handleOrgTag(row: Api.User.Item) {
  editingData.value = row;
  visible.value = true;
}

const filterDraft = ref<Api.User.SearchParams>({ keyword: null, orgTag: null, status: null });

async function searchUsers() {
  updateSearchParams({ ...filterDraft.value });
  await getDataByPage(1);
}

async function resetFilters() {
  filterDraft.value = { keyword: null, orgTag: null, status: null };
  await searchUsers();
}

function handleTokenQuota(row: Api.User.Item) {
  tokenEditingData.value = row;
  tokenVisible.value = true;
}
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch overflow-auto">
    <h1 class="page-title">用户管理</h1>
    <NCard title="用户列表" :bordered="false" size="small" class="page-section sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :addable="false" :loading="loading" @refresh="getData" />
      </template>
      <UserSearch v-model:model="filterDraft" :loading="loading" @reset="resetFilters" @search="searchUsers" />
      <NDataTable
        :bordered="false"
        :columns="columns"
        :data="data"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="1400"
        :loading="loading"
        remote
        :row-key="row => row.userId"
        :pagination="mobilePagination"
        class="sm:h-full"
      >
        <template #empty>
          <div class="table-state">
            <icon-lucide:users class="table-state__icon" />
            <template v-if="hasActiveFilters">
              <p class="table-state__title">没有符合筛选条件的用户</p>
              <p class="table-state__desc">调整关键词或组织标签后重试</p>
              <NButton size="small" :disabled="loading" @click="resetFilters">清除筛选</NButton>
            </template>
            <template v-else>
              <p class="table-state__title">暂无用户</p>
              <p class="table-state__desc">成员通过邀请码注册加入后，账号会汇总到这里</p>
            </template>
          </div>
        </template>
      </NDataTable>
    </NCard>

    <OrgTagSettingDialog v-model:visible="visible" :row-data="editingData!" @submitted="getData" />
    <TokenQuotaDialog v-model:visible="tokenVisible" :row-data="tokenEditingData!" @submitted="getData" />
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

.table-state__icon {
  font-size: 40px;
  color: rgb(var(--base-text-color) / 0.24);
}

.table-state__title {
  margin: 8px 0 0;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--app-text);
}

.table-state__desc {
  margin: 0;
  max-width: 360px;
  font-size: 13px;
  line-height: 1.5;
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
</style>
