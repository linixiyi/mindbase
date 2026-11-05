<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref, watch } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { NButton, NCard, NDataTable, NInputNumber, NModal, NSpace, NSpin, NTabPane, NTabs, NTag } from 'naive-ui';
import QRCode from 'qrcode.vue';
import {
  fetchCreateRechargeOrder,
  fetchRechargeOrderDetail,
  fetchRechargeOrders,
  fetchRechargePackages
} from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';

const TOKEN_UNIT = 10000;

const authStore = useAuthStore();
const loading = ref(false);
const packagesError = ref(false);
const packages = ref<Api.Recharge.Package[]>([]);
const selectedPackageId = ref<number | null>(null);
const customAmountYuan = ref<number | null>(null); // 自定义充值金额（元）
const isCustomAmount = ref(false);

// 充值记录相关
const orderListLoading = ref(false);
const ordersError = ref(false);
const orders = ref<Api.Recharge.Order[]>([]);
const activeTab = ref<string>('all');
const isFiltered = computed(() => activeTab.value !== 'all');

// 支付弹窗相关
const showPayModal = ref(false);
const payLoading = ref(false);
const orderInfo = ref<Api.Recharge.OrderInfo | null>(null);
const currentTradeNo = ref<string>('');

// 获取当前订单金额（分）
const getOrderAmount = computed(() => {
  if (isCustomAmount.value && customAmountYuan.value) {
    return Math.round(customAmountYuan.value * 100);
  }
  if (selectedPackageId.value) {
    const pkg = packages.value.find(p => p.id === selectedPackageId.value);
    return pkg?.packagePrice || 0;
  }
  return 0;
});

// 验证自定义充值金额是否有效
const isValidCustomAmount = computed(() => {
  if (!isCustomAmount.value || !customAmountYuan.value) return false;
  return customAmountYuan.value >= 0.01;
});

function formatTokenWan(value: number) {
  const wan = value / TOKEN_UNIT;
  const hasDecimal = Math.abs(wan - Math.round(wan)) > 0.000001;
  const formatted = wan.toLocaleString('zh-CN', {
    minimumFractionDigits: hasDecimal ? 2 : 0,
    maximumFractionDigits: hasDecimal ? 2 : 0
  });
  return `${formatted} 万`;
}

function packageBenefitLines(content?: string | null) {
  return (content || '')
    .split('\n')
    .map(item => item.trim())
    .filter(Boolean);
}

// 获取充值套餐列表
const getPackages = async () => {
  loading.value = true;
  const { error, data } = await fetchRechargePackages();
  if (!error && data) {
    packages.value = data;
    packagesError.value = false;
  } else {
    packagesError.value = true;
  }
  loading.value = false;
};

// 获取充值记录列表
const getOrders = async () => {
  orderListLoading.value = true;
  try {
    const status = activeTab.value === 'all' ? undefined : (activeTab.value as any);
    const { error, data } = await fetchRechargeOrders(status);
    if (!error && data) {
      orders.value = data;
      ordersError.value = false;
    } else {
      ordersError.value = true;
    }
  } finally {
    orderListLoading.value = false;
  }
};

// 切换标签页时刷新订单列表
const handleTabChange = (tab: string) => {
  activeTab.value = tab;
  getOrders();
};

// 选择套餐
const selectPackage = (packageId: number) => {
  selectedPackageId.value = packageId;
  isCustomAmount.value = false;
  customAmountYuan.value = null;
};

// 选择自定义金额
const selectCustomAmount = () => {
  isCustomAmount.value = true;
  selectedPackageId.value = null;
};

// 创建订单
const createOrder = async () => {
  if (!isCustomAmount.value && !selectedPackageId.value) {
    window.$message?.warning('请选择充值套餐或输入充值金额');
    return;
  }

  if (isCustomAmount.value && !isValidCustomAmount.value) {
    window.$message?.warning('请输入有效的充值金额，至少 1 元');
    return;
  }

  payLoading.value = true;
  try {
    const { error, data } = await fetchCreateRechargeOrder(
      isCustomAmount.value ? undefined : selectedPackageId.value || undefined,
      isCustomAmount.value ? getOrderAmount.value : undefined
    );

    if (!error && data) {
      orderInfo.value = data;
      currentTradeNo.value = data.outTradeNo;
      showPayModal.value = true;
      window.$message?.success('订单创建成功，请扫码支付');
    }
  } finally {
    payLoading.value = false;
  }
};

// 检查支付状态；silent=true 时为自动轮询：未支付不弹提示、瞬时错误不打扰，终态才提示一次
const checkPayStatus = async (silent = false) => {
  if (!currentTradeNo.value) return;

  try {
    const { error, data } = await fetchRechargeOrderDetail(currentTradeNo.value);
    if (!error && data) {
      if (data.status === 'SUCCEED') {
        stopPayPolling();
        window.$message?.success('充值成功！');
        showPayModal.value = false;
        // 刷新用户信息
        await authStore.initUserInfo();
        // 刷新订单列表
        getOrders();
      } else if (data.status === 'NOT_PAY' || data.status === 'PAYING') {
        if (!silent) window.$message?.info('尚未支付，请继续扫码支付');
      } else {
        stopPayPolling();
        window.$message?.error('支付失败，请重试');
      }
    } else if (!silent) {
      window.$message?.error('查询支付状态失败');
    }
  } catch {
    if (!silent) window.$message?.error('查询支付状态失败');
  }
};

// pm-027：弹窗打开期间自动轮询支付状态（5s），终态或关闭后停止
const PAY_POLL_INTERVAL = 5000;
let payPollTimer: ReturnType<typeof setInterval> | null = null;

function stopPayPolling() {
  if (payPollTimer) {
    clearInterval(payPollTimer);
    payPollTimer = null;
  }
}

function startPayPolling() {
  stopPayPolling();
  payPollTimer = setInterval(() => {
    if (document.hidden) return;
    checkPayStatus(true);
  }, PAY_POLL_INTERVAL);
}

watch(showPayModal, show => {
  if (show) {
    startPayPolling();
  } else {
    stopPayPolling();
  }
});

onUnmounted(() => {
  stopPayPolling();
});

// 定义订单表格列
const orderColumns = computed<DataTableColumns<Api.Recharge.Order>>(() => [
  {
    key: 'tradeNo',
    title: '订单号',
    width: 200,
    ellipsis: {
      tooltip: true
    }
  },
  {
    key: 'description',
    title: '充值说明',
    minWidth: 200,
    ellipsis: {
      tooltip: true
    }
  },
  {
    key: 'amount',
    title: '充值金额',
    width: 120,
    render: row => `¥${(row.amount / 100).toFixed(2)}`
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
    key: 'status',
    title: '状态',
    width: 100,
    render: row => {
      const statusMap: Record<string, { text: string; type: any }> = {
        NOT_PAY: { text: '未支付', type: 'default' },
        PAYING: { text: '支付中', type: 'warning' },
        SUCCEED: { text: '支付成功', type: 'success' },
        FAIL: { text: '支付失败', type: 'error' },
        CANCELLED: { text: '已取消', type: 'default' }
      };
      const status = statusMap[row.status] || { text: '未知', type: 'default' };
      return h(NTag, { type: status.type }, () => status.text);
    }
  },
  {
    key: 'payTime',
    title: '支付时间',
    width: 180,
    render: row => (row.payTime ? new Date(row.payTime).toLocaleString('zh-CN') : '-')
  },
  {
    key: 'createdAt',
    title: '创建时间',
    width: 180,
    render: row => new Date(row.createdAt).toLocaleString('zh-CN')
  }
]);

onMounted(() => {
  getPackages();
  getOrders();
});
</script>

<template>
  <div class="workspace-page min-h-500px flex-col-stretch gap-16px overflow-auto">
    <h1 class="page-title">余额充值</h1>
    <NCard title="余额充值" :bordered="false" size="small" class="page-section">
      <!-- 余额充值区域 -->
      <template #header-extra>
        <NTag :bordered="false">微信支付</NTag>
      </template>

      <NSpin :show="loading">
        <div v-if="packagesError" class="page-state">
          <div class="app-state-badge app-state-badge--error">
            <icon-lucide:circle-alert class="text-icon-large" />
          </div>
          <p class="page-state__title">充值套餐加载失败</p>
          <p class="page-state__desc">网络异常导致套餐没有加载，请检查连接后重试</p>
          <NButton size="small" :disabled="loading" @click="getPackages">
            <template #icon>
              <icon-lucide:rotate-cw />
            </template>
            重试
          </NButton>
        </div>
        <div v-else-if="packages.length > 0" class="grid gap-6 lg:grid-cols-3 md:grid-cols-2">
          <!-- 充值套餐卡片 -->
          <NCard
            v-for="pkg in packages"
            :key="pkg.id"
            :bordered="false"
            class="package-option"
            :class="{ 'is-selected': selectedPackageId === pkg.id && !isCustomAmount }"
            tabindex="0"
            @click="selectPackage(pkg.id)"
            @keydown.enter.prevent="selectPackage(pkg.id)"
            @keydown.space.prevent="selectPackage(pkg.id)"
          >
            <div class="flex flex-col gap-4">
              <h3 class="package-name">{{ pkg.packageName }}</h3>

              <div class="flex items-baseline gap-2">
                <span class="package-price">¥{{ (pkg.packagePrice / 100).toFixed(2) }}</span>
              </div>

              <div class="token-lines flex flex-col gap-2">
                <div class="flex items-center gap-2">
                  <icon-lucide:coins class="text-tertiary" />
                  <span>
                    LLM Token:
                    <strong>{{ formatTokenWan(pkg.llmToken) }}</strong>
                  </span>
                </div>
                <div class="flex items-center gap-2">
                  <icon-lucide:coins class="text-tertiary" />
                  <span>
                    Embedding Token:
                    <strong>{{ formatTokenWan(pkg.embeddingToken) }}</strong>
                  </span>
                </div>
              </div>

              <NEllipsis :line-clamp="2" class="package-desc">
                {{ pkg.packageDesc }}
              </NEllipsis>

              <div v-if="packageBenefitLines(pkg.packageBenefit).length" class="package-perks flex flex-col gap-2 p-3">
                <div
                  v-for="line in packageBenefitLines(pkg.packageBenefit)"
                  :key="`${pkg.id}-${line}`"
                  class="flex items-start gap-2"
                >
                  <span class="perk-dot"></span>
                  <span>{{ line }}</span>
                </div>
              </div>

              <NButton
                v-if="selectedPackageId === pkg.id && !isCustomAmount"
                type="primary"
                block
                size="large"
                @click.stop="createOrder"
              >
                立即充值
              </NButton>
              <NButton v-else secondary block size="large">选择套餐</NButton>
            </div>
          </NCard>

          <!-- 自定义充值卡片 -->
          <NCard
            :bordered="false"
            class="package-option"
            :class="{ 'is-selected': isCustomAmount }"
            tabindex="0"
            @click="selectCustomAmount"
            @keydown.enter.prevent="selectCustomAmount"
            @keydown.space.prevent="selectCustomAmount"
          >
            <div class="flex flex-col gap-4">
              <h3 class="package-name">自定义充值</h3>

              <div class="flex items-baseline gap-2">
                <span class="package-price">灵活充值</span>
              </div>

              <div class="package-desc flex flex-col gap-2">
                <div class="flex items-start gap-2">
                  <span class="perk-dot"></span>
                  <span>充值金额不限</span>
                </div>
                <div class="flex items-start gap-2">
                  <span class="perk-dot"></span>
                  <span>按比例赠送 Token</span>
                </div>
                <div class="flex items-start gap-2">
                  <span class="perk-dot"></span>
                  <span>随时充值随时用</span>
                </div>
              </div>

              <div v-if="isCustomAmount" class="mt-4" @click.stop>
                <NInputNumber
                  v-model:value="customAmountYuan"
                  :min="0.01"
                  :step="1"
                  :precision="2"
                  placeholder="请输入充值金额（元）"
                  class="w-full"
                  size="large"
                  type="text"
                />
                <div class="text-muted mt-2 text-xs">建议至少充值 1 元，自动转换为分进行支付</div>
              </div>

              <NButton
                v-if="isCustomAmount && isValidCustomAmount"
                type="primary"
                block
                size="large"
                @click.stop="createOrder"
              >
                立即充值 ¥{{ customAmountYuan }}
              </NButton>
              <NButton v-else secondary block size="large">自定义金额</NButton>
            </div>
          </NCard>
        </div>

        <div v-else class="page-state">
          <div class="app-state-badge">
            <icon-lucide:package class="text-icon-large" />
          </div>
          <p class="page-state__title">暂无充值套餐</p>
          <p class="page-state__desc">当前没有可用的充值套餐，请稍后再来看看</p>
        </div>
      </NSpin>
    </NCard>

    <!-- 充值记录列表 -->
    <NCard title="充值记录" :bordered="false" size="large" class="page-section w-full">
      <NTabs v-model:value="activeTab" @update:value="handleTabChange">
        <NTabPane name="all" tab="全部订单" />
        <NTabPane name="NOT_PAY" tab="未支付" />
        <NTabPane name="PAYING" tab="支付中" />
        <NTabPane name="SUCCEED" tab="支付成功" />
        <NTabPane name="FAIL" tab="支付失败" />
      </NTabs>

      <NSpin :show="orderListLoading">
        <div v-if="ordersError" class="page-state">
          <div class="app-state-badge app-state-badge--error">
            <icon-lucide:circle-alert class="text-icon-large" />
          </div>
          <p class="page-state__title">充值记录加载失败</p>
          <p class="page-state__desc">网络异常导致记录没有加载，请检查连接后重试</p>
          <NButton size="small" :disabled="orderListLoading" @click="getOrders">
            <template #icon>
              <icon-lucide:rotate-cw />
            </template>
            重试
          </NButton>
        </div>
        <NDataTable
          v-else-if="orders.length > 0"
          :bordered="false"
          :columns="orderColumns"
          :data="orders"
          :loading="orderListLoading"
          :scroll-x="1200"
          class="mt-4"
        />
        <div v-else class="page-state">
          <div class="app-state-badge">
            <icon-lucide:credit-card class="text-icon-large" />
          </div>
          <template v-if="isFiltered">
            <p class="page-state__title">没有该状态下的订单</p>
            <p class="page-state__desc">当前筛选下没有订单，可以查看全部订单</p>
            <NButton size="small" @click="handleTabChange('all')">查看全部订单</NButton>
          </template>
          <template v-else>
            <p class="page-state__title">暂无充值记录</p>
            <p class="page-state__desc">发起充值并完成支付后，订单会汇总到这里</p>
          </template>
        </div>
      </NSpin>
    </NCard>

    <!-- 支付二维码弹窗 -->
    <NModal v-model:show="showPayModal" preset="dialog" title="扫码支付" :show-icon="false" class="w-480px">
      <div class="flex flex-col items-center gap-6 py-6">
        <div v-if="orderInfo" class="flex flex-col items-center gap-4">
          <!-- QR 码是内容而非界面：黑白高对比是扫码识别的硬性条件，不随主题变化 -->
          <div class="qr-shell">
            <QRCode
              :value="orderInfo.prePayId"
              :size="240"
              level="H"
              include-margin
              render-as="svg"
              foreground="#000000"
              background="#ffffff"
            />
          </div>
          <div class="text-center">
            <p class="text-16px font-semibold">请使用微信扫码支付</p>
            <p class="text-muted mt-2 text-14px leading-20px font-mono">订单号：{{ currentTradeNo }}</p>
            <p class="text-muted mt-1 text-14px leading-20px tabular-nums">
              支付金额：¥{{ (getOrderAmount / 100).toFixed(2) }}
            </p>
          </div>
        </div>

        <NSpace vertical class="w-full px-8">
          <NButton type="primary" size="large" block @click="checkPayStatus()">我已完成支付</NButton>
        </NSpace>
      </div>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
/* 可选套餐磁贴：卡片内的小面板 → md 14px，静态零阴影，hover 以底色回应 */
.package-option {
  cursor: pointer;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-md);
  background: var(--app-fill-subtle);
  box-shadow: none;
  transition:
    background-color var(--app-dur-fast) var(--app-ease-standard),
    border-color var(--app-dur-fast) var(--app-ease-standard),
    box-shadow var(--app-dur-fast) var(--app-ease-standard);

  &:hover {
    background: var(--app-fill-hover);
  }

  &:focus-visible {
    outline: 2px solid rgb(var(--primary-color));
    outline-offset: 2px;
  }

  &.is-selected {
    border-color: rgb(var(--primary-color));
    box-shadow: 0 0 0 1px rgb(var(--primary-color));
  }
}
.package-name {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--app-text);
}
.package-price {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.25;
  color: var(--app-text);
  font-variant-numeric: tabular-nums;
}
.token-lines {
  font-size: 14px;
  line-height: 1.55;
  color: var(--app-text-secondary);

  strong {
    font-weight: 600;
    color: var(--app-text);
    font-variant-numeric: tabular-nums;
  }
}
.package-desc {
  font-size: 14px;
  line-height: 1.55;
  color: var(--app-muted);
}
.package-perks {
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  font-size: 14px;
  line-height: 1.55;
  color: var(--app-text-secondary);
}
.perk-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  margin-top: 7px;
  border-radius: 9999px;
  background: var(--app-text-tertiary);
}
.qr-shell {
  padding: 24px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-md);
  background: var(--app-surface);
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
