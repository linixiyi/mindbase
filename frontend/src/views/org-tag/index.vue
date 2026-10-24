<script setup lang="tsx">
import { NButton, NPopconfirm } from 'naive-ui';
import { fetchGetOrgTagList } from '@/service/api/org-tag';
import OrgTagOperateDialog from './modules/org-tag-operate-dialog.vue';

const appStore = useAppStore();

const { columns, columnChecks, data, loading, getData, mobilePagination } = useTable({
  apiFn: fetchGetOrgTagList,
  showTotal: true,
  columns: () => [
    {
      key: 'name',
      title: '标签名称',
      width: 300,
      ellipsis: {
        tooltip: true
      }
    },
    {
      key: 'description',
      title: '描述',
      minWidth: 200,
      ellipsis: {
        tooltip: true
      }
    },
    {
      key: 'uploadMaxSizeMb',
      title: '非Admin上传上限',
      width: 160,
      render: row => (row.uploadMaxSizeMb ? `${row.uploadMaxSizeMb} MB` : '不限制')
    },
    {
      key: 'operate',
      title: '操作',
      width: 240,
      render: row => (
        <div class="flex gap-2">
          <NButton quaternary size="small" onClick={() => addChild(row)}>
            新增下级
          </NButton>
          <NButton type="primary" quaternary size="small" onClick={() => edit(row)}>
            编辑
          </NButton>
          <NPopconfirm onPositiveClick={() => handleDelete(row.tagId!)}>
            {{
              default: () => '确认删除当前标签吗？',
              trigger: () => (
                <NButton type="error" quaternary size="small">
                  删除
                </NButton>
              )
            }}
          </NPopconfirm>
        </div>
      )
    }
  ]
});

const {
  dialogVisible,
  operateType,
  editingData,
  handleAdd,
  handleAddChild,
  handleEdit,
  onDeleted
  // closeDrawer
} = useTableOperate<Api.OrgTag.Item>(getData);

function addChild(row: Api.OrgTag.Item) {
  handleAddChild(row);
}

/** the editing row data */
function edit(row: Api.OrgTag.Item) {
  handleEdit(row);
}

async function handleDelete(tagId: string) {
  const { error } = await request({ url: `/admin/org-tags/${tagId}`, method: 'DELETE' });
  if (!error) {
    onDeleted();
  }
}
</script>

<template>
  <div class="workspace-page flex-col-stretch overflow-hidden <sm:overflow-auto">
    <div class="page-header">
      <h1 class="page-title">组织标签</h1>
      <NButton type="primary" :disabled="loading" @click="handleAdd">新增标签</NButton>
    </div>
    <NCard title="组织标签" :bordered="false" size="small" class="page-section sm:flex-1-hidden">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :addable="false" :loading="loading" @refresh="getData" />
      </template>
      <NDataTable
        :bordered="false"
        remote
        :columns="columns"
        :data="data"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="962"
        :loading="loading"
        :pagination="mobilePagination"
        :row-key="item => item.tagId"
        class="sm:h-full"
      >
        <template #empty>
          <div class="table-state">
            <div class="app-state-badge">
              <icon-lucide:tags class="text-icon-large" />
            </div>
            <p class="table-state__title">暂无组织标签</p>
            <p class="table-state__desc">组织标签用于划分知识库可见范围与上传配额</p>
            <NButton size="small" type="primary" :disabled="loading" @click="handleAdd">新增标签</NButton>
          </div>
        </template>
      </NDataTable>
      <OrgTagOperateDialog
        v-model:visible="dialogVisible"
        :operate-type="operateType"
        :row-data="editingData!"
        :data="data"
        @submitted="getData"
      />
    </NCard>
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

/* R11 delight：空态图标改吃 global.scss 的 azure 徽标 tile（.app-state-badge）——
   裸灰 ghost 图标就此从空态退役，__icon 规则随之删除 */
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
</style>
