<script setup lang="tsx">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import type { DataTableColumns, FormRules, PaginationProps } from 'naive-ui';
import {
  NButton,
  NCard,
  NEllipsis,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  NSwitch,
  NTag
} from 'naive-ui';
import { fetchAdminRechargeOrders } from '@/service/api';
import { request } from '@/service/request';

const TOKEN_UNIT = 10000;

// pm-027：进行中订单轮询间隔（5-10s 区间内取 8s），页面离开即停
const ORDER_POLL_INTERVAL = 8000;
const IN_PROGRESS_STATUSES = ['NOT_PAY', 'PAYING'];

interface RechargePackage {
  id: number;
  packageName: string;
  packagePrice: number;
  packageDesc: string | null;
  packageBenefit: string | null;
  llmToken: number;
  embeddingToken: number;
  enabled: boolean;
  deleted: boolean;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}

interface RechargePackageForm {
  packageName: string;
  packagePriceYuan: number | null; // 套餐价格（元）
  packageDesc: string;
  packageBenefit: string;
  llmTokenWan: number | null;
  embeddingTokenWan: number | null;
  enabled: boolean;
  sortOrder: number | null;
}

const loading = ref(false);
const loadError = ref(false);
const data = ref<RechargePackage[]>([]);
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

const visible = ref(false);
const submitting = ref(false);
const editingId = ref<number | null>(null);
const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const model = ref<RechargePackageForm>(createDefaultModel());

const rules = ref<FormRules>({
  packageName: [defaultRequiredRule],
  packagePriceYuan: [
    defaultRequiredRule,
    {
      validator(_, value) {
        return value !== null && value > 0;
      },
      message: '套餐价格必须大于 0',
      trigger: ['blur', 'change']
    }
  ],
  llmTokenWan: [
    defaultRequiredRule,
    {
      validator(_, value) {
        return value !== null && value >= 0;
      },
      message: 'LLM Token 数量不能为负数',
      trigger: ['blur', 'change']
    }
  ],
  embeddingTokenWan: [
    defaultRequiredRule,
    {
      validator(_, value) {
        return value !== null && value >= 0;
      },
      message: 'Embedding Token 数量不能为负数',
      trigger: ['blur', 'change']
    }
  ]
});

function createDefaultModel(): RechargePackageForm {
  return {
    packageName: '',
    packagePriceYuan: null,
    packageDesc: '',
    packageBenefit: '',
    llmTokenWan: null,
    embeddingTokenWan: null,
    enabled: true,
    sortOrder: 0
  };
}

function formatWanValue(value: number) {
  const wan = value / TOKEN_UNIT;
  const hasDecimal = Math.abs(wan - Math.round(wan)) > 0.000001;
  return wan.toLocaleString('zh-CN', {
    minimumFractionDigits: hasDecimal ? 2 : 0,
    maximumFractionDigits: hasDecimal ? 2 : 0
  });
}

function formatTokenWan(value: number) {
  return `${formatWanValue(value)} 万`;
}

function buildPackageDesc() {
  const price = model.value.packagePriceYuan ?? 0;
  const llmTokenWan = model.value.llmTokenWan ?? 0;
  const embeddingTokenWan = model.value.embeddingTokenWan ?? 0;

  if (price >= 49.9 || llmTokenWan >= 1000 || embeddingTokenWan >= 500) {
    return '适合高频问答、团队共享资料和较大规模知识库场景。';
  }

  if (price >= 19.9 || llmTokenWan >= 500 || embeddingTokenWan >= 200) {
    return '适合持续问答、资料整理和中等规模知识库构建。';
  }

  return '适合轻度体验、日常问答和少量知识库上传。';
}

function buildPackageBenefit() {
  const llmTokenWan = model.value.llmTokenWan ?? 0;
  const embeddingTokenWan = model.value.embeddingTokenWan ?? 0;

  return [
    `LLM Token：${llmTokenWan.toLocaleString('zh-CN', { maximumFractionDigits: 2 })} 万`,
    `Embedding Token：${embeddingTokenWan.toLocaleString('zh-CN', { maximumFractionDigits: 2 })} 万`,
    '支持微信支付充值',
    '余额到账后可直接使用'
  ].join('\n');
}

function autofillPackageCopy() {
  model.value.packageDesc = buildPackageDesc();
  model.value.packageBenefit = buildPackageBenefit();
}

const isEditing = computed(() => editingId.value !== null);

// ==================== 充值订单列表（pm-027） ====================
interface AdminRechargeOrder {
  id: number;
  tradeNo: string;
  userId: string;
  username: string | null;
  amount: number;
  llmToken: number;
  embeddingToken: number;
  status: string;
  description: string | null;
  payTime: string | null;
  createdAt: string;
}

const orderStatusOptions = [
  { label: '全部状态', value: 'all' },
  { label: '待支付', value: 'NOT_PAY' },
  { label: '支付中', value: 'PAYING' },
  { label: '支付成功', value: 'SUCCEED' },
  { label: '支付失败', value: 'FAIL' },
  { label: '已取消', value: 'CANCELLED' }
];

const orderStatusMap: Record<string, { text: string; type: 'default' | 'warning' | 'success' | 'error' }> = {
  NOT_PAY: { text: '待支付', type: 'default' },
  PAYING: { text: '支付中', type: 'warning' },
  SUCCEED: { text: '支付成功', type: 'success' },
  FAIL: { text: '支付失败', type: 'error' },
  CANCELLED: { text: '已取消', type: 'default' }
};

const ordersLoading = ref(false);
const ordersError = ref(false);
const orderData = ref<AdminRechargeOrder[]>([]);
const orderStatusFilter = ref('all');
const appliedOrderStatus = ref('all');
let orderPollTimer: ReturnType<typeof setInterval> | null = null;

const orderPagination = reactive<PaginationProps>({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 15, 20, 25, 30],
  onUpdatePage: async (page: number) => {
    orderPagination.page = page;
    await getOrders();
  },
  onUpdatePageSize: async (pageSize: number) => {
    orderPagination.pageSize = pageSize;
    orderPagination.page = 1;
    await getOrders();
  }
});

const orderColumns = computed<DataTableColumns<AdminRechargeOrder>>(() => [
  {
    key: 'tradeNo',
    title: '订单号',
    width: 210,
    ellipsis: { tooltip: true },
    render: row => <span class="text-3.5 font-mono">{row.tradeNo}</span>
  },
  {
    key: 'username',
    title: '用户',
    width: 130,
    render: row => row.username || row.userId || '-'
  },
  {
    key: 'description',
    title: '充值说明',
    minWidth: 180,
    ellipsis: { tooltip: true },
    render: row => <NEllipsis line-clamp={1}>{row.description || '-'}</NEllipsis>
  },
  {
    key: 'amount',
    title: '金额',
    width: 100,
    render: row => `¥${(row.amount / 100).toFixed(2)}`
  },
  {
    key: 'llmToken',
    title: 'LLM Token（万）',
    width: 130,
    render: row => formatTokenWan(row.llmToken)
  },
  {
    key: 'embeddingToken',
    title: 'Embedding Token（万）',
    width: 150,
    render: row => formatTokenWan(row.embeddingToken)
  },
  {
    key: 'status',
    title: '状态',
    width: 96,
    render: row => {
      const status = orderStatusMap[row.status] || { text: '未知', type: 'default' as const };
      return (
        <NTag bordered={false} type={status.type}>
          {status.text}
        </NTag>
      );
    }
  },
  {
    key: 'payTime',
    title: '支付时间',
    width: 170,
    render: row => (row.payTime ? dayjs(row.payTime).format('YYYY-MM-DD HH:mm:ss') : '-')
  },
  {
    key: 'createdAt',
    title: '创建时间',
    width: 170,
    render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss')
  }
]);

async function getOrders(silent = false) {
  // 轮询触发的静默刷新不闪 loading，避免表格反复抖动
  if (!silent) ordersLoading.value = true;
  try {
    const { error, data: resData } = await fetchAdminRechargeOrders({
      page: Number(orderPagination.page),
      size: Number(orderPagination.pageSize),
      status: appliedOrderStatus.value === 'all' ? undefined : appliedOrderStatus.value
    });

    if (!error && resData) {
      orderData.value = resData.records || [];
      orderPagination.itemCount = resData.total || 0;
      ordersError.value = false;
      syncOrderPolling();
    } else {
      ordersError.value = true;
      stopOrderPolling();
    }
  } finally {
    if (!silent) ordersLoading.value = false;
  }
}

async function searchOrders() {
  appliedOrderStatus.value = orderStatusFilter.value;
  orderPagination.page = 1;
  await getOrders();
}

async function resetOrderFilters() {
  orderStatusFilter.value = 'all';
  await searchOrders();
}

function hasInProgressOrder() {
  return orderData.value.some(row => IN_PROGRESS_STATUSES.includes(row.status));
}

/** 依据当前页数据启停轮询：仅当本页存在待支付/支付中订单时保持 8s 刷新 */
function syncOrderPolling() {
  if (hasInProgressOrder()) {
    if (!orderPollTimer) {
      orderPollTimer = setInterval(() => {
        if (document.hidden) return;
        getOrders(true);
      }, ORDER_POLL_INTERVAL);
    }
  } else {
    stopOrderPolling();
  }
}

function stopOrderPolling() {
  if (orderPollTimer) {
    clearInterval(orderPollTimer);
    orderPollTimer = null;
  }
}

onBeforeUnmount(() => {
  stopOrderPolling();
});

const columns = computed<DataTableColumns<RechargePackage>>(() => [
  {
    key: 'sortOrder',
    title: '排序',
    width: 70
  },
  {
    key: 'id',
    title: 'ID',
    width: 60
  },
  {
    key: 'packageName',
    title: '套餐名称',
    width: 150
  },
  {
    key: 'packagePrice',
    title: '套餐价格',
    width: 120,
    render: row => `¥${(row.packagePrice / 100).toFixed(2)}`
  },
  {
    key: 'llmToken',
    title: 'LLM Token（万）',
    width: 120,
    render: row => formatTokenWan(row.llmToken)
  },
  {
    key: 'embeddingToken',
    title: 'Embedding Token（万）',
    width: 140,
    render: row => formatTokenWan(row.embeddingToken)
  },
  {
    key: 'enabled',
    title: '状态',
    width: 80,
    render: row => (
      <NTag bordered={false} type={row.enabled ? 'success' : 'default'}>
        {row.enabled ? '已启用' : '已禁用'}
      </NTag>
    )
  },
  {
    key: 'packageDesc',
    title: '描述',
    minWidth: 200,
    ellipsis: {
      tooltip: true
    },
    render: row => <NEllipsis line-clamp={1}>{row.packageDesc || '-'}</NEllipsis>
  },
  {
    key: 'createdAt',
    title: '创建时间',
    width: 180,
    render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss')
  },
  {
    key: 'actions',
    title: '操作',
    width: 180,
    fixed: 'right',
    render: row => (
      <div class="flex gap-2">
        <NButton quaternary size="small" onClick={() => handleEdit(row)}>
          编辑
        </NButton>
        <NPopconfirm onPositiveClick={() => handleDelete(row.id)}>
          {{
            trigger: () => (
              <NButton quaternary size="small" type="error">
                删除
              </NButton>
            ),
            default: () => '确定删除该充值套餐吗？'
          }}
        </NPopconfirm>
      </div>
    )
  }
]);

async function getData() {
  loading.value = true;
  try {
    const { error, data: resData } = await request<RechargePackage[]>({
      url: '/admin/recharge-packages',
      method: 'get'
    });

    if (!error && resData) {
      data.value = resData;
      pagination.itemCount = resData.length;
      loadError.value = false;
    } else {
      loadError.value = true;
    }
  } finally {
    loading.value = false;
  }
}

function handleCreate() {
  editingId.value = null;
  model.value = createDefaultModel();
  visible.value = true;
  restoreValidation();
}

function handleEdit(row: RechargePackage) {
  editingId.value = row.id;
  model.value = {
    packageName: row.packageName,
    packagePriceYuan: row.packagePrice / 100, // 分转元
    packageDesc: row.packageDesc || '',
    packageBenefit: row.packageBenefit || '',
    llmTokenWan: row.llmToken / TOKEN_UNIT,
    embeddingTokenWan: row.embeddingToken / TOKEN_UNIT,
    enabled: row.enabled,
    sortOrder: row.sortOrder
  };
  visible.value = true;
}

function buildSubmitPayload() {
  // 将元转换为分
  const submitData = {
    ...model.value,
    packagePrice: model.value.packagePriceYuan !== null ? Math.round(model.value.packagePriceYuan * 100) : null,
    llmToken: model.value.llmTokenWan !== null ? Math.round(model.value.llmTokenWan * TOKEN_UNIT) : null,
    embeddingToken:
      model.value.embeddingTokenWan !== null ? Math.round(model.value.embeddingTokenWan * TOKEN_UNIT) : null
  };
  // 删除 packagePriceYuan 字段，不传递给后端
  delete (submitData as any).packagePriceYuan;
  delete (submitData as any).llmTokenWan;
  delete (submitData as any).embeddingTokenWan;
  return submitData;
}

function handleFormSubmitError(e: any) {
  if (Array.isArray(e)) {
    window.$message?.error('请填写完整的表单信息!');
    return;
  }
  // validate 失败时会抛出异常，包含验证错误信息
  if (e?.errors) {
    // errors 可能是一个数组，取第一个错误的提示信息
    const firstError = Array.isArray(e.errors) ? e.errors[0] : e.errors;
    const errorMessage = firstError?.message || '请填写完整的表单信息';
    window.$message?.warning(errorMessage);
  } else {
    window.$message?.error(`操作失败：${(e as Error).message}`);
  }
}

async function handleSubmit() {
  try {
    // 先进行表单验证
    await validate();

    submitting.value = true;

    const url = isEditing.value ? `/admin/recharge-packages/${editingId.value}` : '/admin/recharge-packages';

    const method = isEditing.value ? 'put' : 'post';

    const submitData = buildSubmitPayload();

    const { error } = await request({
      url,
      method,
      data: submitData
    });

    if (!error) {
      window.$message?.success(isEditing.value ? '更新成功' : '创建成功');
      visible.value = false;
      await getData();
    } else {
      window.$message?.error(`操作失败：${error.message || '未知错误'}`);
    }
  } catch (e: any) {
    handleFormSubmitError(e);
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(id: number) {
  try {
    const { error } = await request({
      url: `/admin/recharge-packages/${id}`,
      method: 'delete'
    });

    if (!error) {
      window.$message?.success('删除成功');
      await getData();
    }
  } catch {
    window.$message?.error('删除失败');
  }
}

onMounted(() => {
  getData();
  getOrders();
});
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch gap-16px overflow-auto">
    <div class="page-header">
      <h1 class="page-title">充值管理</h1>
      <NButton type="primary" @click="handleCreate">新增套餐</NButton>
    </div>
    <NCard title="充值套餐管理" :bordered="false" size="small" class="page-section">
      <div v-if="loadError" class="page-state">
        <div class="app-state-badge app-state-badge--error">
          <icon-lucide:circle-alert class="text-icon-large" />
        </div>
        <p class="page-state__title">套餐列表加载失败</p>
        <p class="page-state__desc">网络异常导致套餐没有加载，请检查连接后重试</p>
        <NButton size="small" :disabled="loading" @click="getData">
          <template #icon>
            <icon-lucide:rotate-cw />
          </template>
          重试
        </NButton>
      </div>
      <NDataTable
        v-else
        :bordered="false"
        :columns="columns"
        :data="data"
        :loading="loading"
        :pagination="pagination"
        :scroll-x="1200"
        class="mt-4"
      >
        <template #empty>
          <div class="page-state page-state--table">
            <div class="app-state-badge">
              <icon-lucide:package class="text-icon-large" />
            </div>
            <p class="page-state__title">暂无充值套餐</p>
            <p class="page-state__desc">点击右上角「新增套餐」创建第一个套餐</p>
          </div>
        </template>
      </NDataTable>
    </NCard>

    <!-- 充值订单列表（pm-027 对账视图） -->
    <NCard title="充值订单" :bordered="false" size="small" class="page-section">
      <NForm class="page-filters" label-placement="left" :show-feedback="false" inline @submit.prevent="searchOrders">
        <NFormItem label="支付状态">
          <NSelect v-model:value="orderStatusFilter" :options="orderStatusOptions" class="w-200px!" />
        </NFormItem>
        <div class="filter-actions">
          <NButton type="primary" attr-type="submit" :loading="ordersLoading">查询</NButton>
          <NButton :disabled="ordersLoading" @click="resetOrderFilters">重置</NButton>
        </div>
      </NForm>

      <div v-if="ordersError" class="page-state">
        <div class="app-state-badge app-state-badge--error">
          <icon-lucide:circle-alert class="text-icon-large" />
        </div>
        <p class="page-state__title">充值订单加载失败</p>
        <p class="page-state__desc">网络异常导致订单没有加载，请检查连接后重试</p>
        <NButton size="small" :disabled="ordersLoading" @click="getOrders()">
          <template #icon>
            <icon-lucide:rotate-cw />
          </template>
          重试
        </NButton>
      </div>
      <NDataTable
        v-else
        :bordered="false"
        :columns="orderColumns"
        :data="orderData"
        :loading="ordersLoading"
        remote
        :row-key="(row: AdminRechargeOrder) => row.id"
        :pagination="orderPagination"
        :scroll-x="1360"
        class="mt-4"
      >
        <template #empty>
          <div class="page-state page-state--table">
            <div class="app-state-badge">
              <icon-lucide:receipt-text class="text-icon-large" />
            </div>
            <template v-if="appliedOrderStatus !== 'all'">
              <p class="page-state__title">没有符合筛选条件的订单</p>
              <p class="page-state__desc">调整支付状态筛选，或清除筛选查看全部订单</p>
              <NButton size="small" :disabled="ordersLoading" @click="resetOrderFilters">清除筛选</NButton>
            </template>
            <template v-else>
              <p class="page-state__title">暂无充值订单</p>
              <p class="page-state__desc">用户发起充值后订单会汇总到这里，用于对账与状态跟踪</p>
            </template>
          </div>
        </template>
      </NDataTable>
    </NCard>

    <!-- 新增/编辑弹窗 -->
    <NModal
      v-model:show="visible"
      preset="dialog"
      :title="isEditing ? '编辑充值套餐' : '新增充值套餐'"
      :show-icon="false"
      class="w-600px!"
    >
      <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" label-width="120px">
        <NFormItem label="套餐名称" path="packageName">
          <NInput v-model:value="model.packageName" placeholder="请输入套餐名称" />
        </NFormItem>
        <NFormItem label="套餐价格（元）" path="packagePriceYuan">
          <NInputNumber
            v-model:value="model.packagePriceYuan"
            :min="0.01"
            :step="1"
            :precision="2"
            placeholder="请输入套餐价格（单位：元）"
            class="w-full"
            type="text"
          />
        </NFormItem>
        <NFormItem label="LLM Token（万）" path="llmTokenWan">
          <NInputNumber
            v-model:value="model.llmTokenWan"
            :min="0"
            :step="0.1"
            :precision="2"
            placeholder="请输入 LLM Token 数量，单位：万"
            class="w-full"
          />
        </NFormItem>
        <NFormItem label="Embedding Token（万）" path="embeddingTokenWan">
          <NInputNumber
            v-model:value="model.embeddingTokenWan"
            :min="0"
            :step="0.1"
            :precision="2"
            placeholder="请输入 Embedding Token 数量，单位：万"
            class="w-full"
          />
        </NFormItem>
        <NFormItem label="套餐描述" path="packageDesc">
          <div class="w-full flex flex-col gap-2">
            <NInput v-model:value="model.packageDesc" type="textarea" :rows="3" placeholder="请输入套餐描述" />
            <div class="flex justify-end">
              <NButton size="small" secondary @click="autofillPackageCopy">一键生成文案</NButton>
            </div>
          </div>
        </NFormItem>
        <NFormItem label="套餐权益" path="packageBenefit">
          <NInput
            v-model:value="model.packageBenefit"
            type="textarea"
            :rows="3"
            placeholder="请输入套餐权益说明，支持换行"
          />
        </NFormItem>
        <NFormItem label="排序顺序" path="sortOrder">
          <NInputNumber
            v-model:value="model.sortOrder"
            :min="0"
            :step="1"
            placeholder="请输入排序顺序（数字越小越靠前）"
            class="w-full"
          />
          <div class="text-muted mt-2 text-xs">提示：数字越小，在列表中显示越靠前</div>
        </NFormItem>
        <NFormItem label="是否启用" path="enabled">
          <NSwitch v-model:value="model.enabled" :checked-value="true" :unchecked-value="false" />
        </NFormItem>
      </NForm>

      <div class="mt-6 flex justify-end gap-4">
        <NButton @click="visible = false">取消</NButton>
        <NButton type="primary" :loading="submitting" @click="handleSubmit">
          {{ isEditing ? '保存' : '创建' }}
        </NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.page-section {
  min-width: 0;
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
.page-state--table {
  padding: 32px 24px;
}
/* R11 delight：空态图标改吃 global.scss 的 azure 徽标 tile（.app-state-badge，error 变体
   走语义红 wash）——裸灰 ghost 图标就此从空态退役，__icon/--error 规则随之删除 */
.page-state__title {
  margin: 8px 0 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--app-text);
}
.page-state__desc {
  margin: 0;
  max-width: 360px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--app-muted);
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
