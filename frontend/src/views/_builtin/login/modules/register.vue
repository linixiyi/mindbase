<script setup lang="ts">
import { inviteChannelConfig } from '@/constants/invite-channel';
import { normalizeBackendMessage } from '@/service/request/shared';
import { $t } from '@/locales';

defineOptions({
  name: 'Register'
});

const route = useRoute();
const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();

const inviteCodeErrorCodes = new Set([
  'INVITE_CODE_REQUIRED',
  'INVITE_CODE_INVALID',
  'INVITE_CODE_EXPIRED',
  'INVITE_CODE_EXHAUSTED'
]);

interface FormModel {
  username: string;
  password: string;
  confirmPassword: string;
  inviteCode: string;
}

const model: FormModel = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  inviteCode: ''
});

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  const { formRules, defaultRequiredRule, createConfirmPwdRule } = useFormRules();

  return {
    username: formRules.userName,
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password),
    inviteCode: [defaultRequiredRule]
  };
});

const loading = ref(false);
const inviteCodeErrorMessage = ref('');
const inviteChannelKeywords = [...inviteChannelConfig.replyKeywords];

function clearInviteCodeError() {
  inviteCodeErrorMessage.value = '';
}

function resolveRegisterError(error: any) {
  const rawMessage = String(error?.response?.data?.message || error?.message || '');
  const message = normalizeBackendMessage(rawMessage || '注册失败，请稍后重试');

  return {
    rawMessage,
    message
  };
}

async function handleSubmit() {
  clearInviteCodeError();
  await validate();
  loading.value = true;
  const { error } = await fetchRegister(model.username, model.password, model.inviteCode.trim());
  if (!error) {
    window.$message?.success('注册成功');
    toggleLoginModule('pwd-login');
  } else {
    const { rawMessage, message } = resolveRegisterError(error);

    if (inviteCodeErrorCodes.has(rawMessage)) {
      inviteCodeErrorMessage.value = message;
    }
  }
  loading.value = false;
}

function syncInviteCodeFromQuery(inviteCode: unknown) {
  if (typeof inviteCode !== 'string') return;
  model.inviteCode = inviteCode.trim();
}

async function copyInviteKeywords() {
  try {
    await navigator.clipboard.writeText(inviteChannelKeywords.join(' / '));
    window.$message?.success('回复关键词已复制');
  } catch {
    window.$message?.error('复制失败，请手动复制');
  }
}

watch(
  () => route.query.inviteCode,
  inviteCode => {
    syncInviteCodeFromQuery(inviteCode);
  },
  { immediate: true }
);

watch(
  () => model.inviteCode,
  () => {
    if (inviteCodeErrorMessage.value) {
      clearInviteCodeError();
    }
  }
);
</script>

<template>
  <div class="register-layout">
    <aside class="invite-side-panel">
      <div class="invite-side-header">
        <div class="invite-side-title">MindBase 企业知识库，欢迎加入内测。</div>
        <div class="invite-side-desc">
          联系
          <span class="invite-emphasis">{{ inviteChannelConfig.officialAccountName }}</span>
          领取邀请码，或使用同事分享的邀请链接完成注册。
        </div>
      </div>

      <div class="invite-side-bottom">
        <div class="invite-qr-shell">
          <img
            v-if="inviteChannelConfig.qrCodeImageUrl"
            :src="inviteChannelConfig.qrCodeImageUrl"
            alt="二维码"
            class="invite-qr-image"
          />
          <div v-else class="invite-qr-placeholder">
            <icon-lucide:message-circle class="invite-qr-icon" />
            <div class="invite-qr-title">二维码位</div>
            <div class="invite-qr-desc">后续把二维码地址填到 `qrCodeImageUrl`，这里会直接展示扫码入口。</div>
          </div>
        </div>

        <div class="invite-side-steps">
          <div class="invite-step-row">
            <span class="invite-channel-step">1</span>
            <span>
              联系
              <span class="invite-emphasis">{{ inviteChannelConfig.officialAccountName }}</span>
            </span>
          </div>
          <div class="invite-step-row">
            <span class="invite-channel-step">2</span>
            <span class="invite-step-copy">
              后台回复关键字
              <span class="invite-keyword-mark">{{ inviteChannelKeywords.join(' / ') }}</span>
              <NButton text type="primary" class="invite-inline-copy" @click="copyInviteKeywords">复制</NButton>
            </span>
          </div>
          <div class="invite-step-row">
            <span class="invite-channel-step">3</span>
            <span>拿到邀请码后，在右侧完成注册</span>
          </div>
        </div>
      </div>
    </aside>

    <NForm
      ref="formRef"
      :model="model"
      :rules="rules"
      size="large"
      :show-label="false"
      class="register-form-panel"
      @keyup.enter="handleSubmit"
    >
      <NFormItem path="username">
        <NInput v-model:value="model.username" :placeholder="$t('page.login.common.userNamePlaceholder')">
          <template #prefix>
            <icon-lucide:user />
          </template>
        </NInput>
      </NFormItem>
      <NFormItem path="password">
        <NInput
          v-model:value="model.password"
          type="password"
          show-password-on="click"
          :placeholder="$t('page.login.common.passwordPlaceholder')"
        >
          <template #prefix>
            <icon-lucide:key-round />
          </template>
        </NInput>
      </NFormItem>
      <NFormItem path="confirmPassword">
        <NInput
          v-model:value="model.confirmPassword"
          type="password"
          show-password-on="click"
          :placeholder="$t('page.login.common.confirmPasswordPlaceholder')"
        >
          <template #prefix>
            <icon-lucide:key-round />
          </template>
        </NInput>
      </NFormItem>
      <NFormItem
        path="inviteCode"
        :validation-status="inviteCodeErrorMessage ? 'error' : undefined"
        :feedback="inviteCodeErrorMessage || undefined"
      >
        <NInput v-model:value="model.inviteCode" :placeholder="$t('page.login.common.inviteCodePlaceholder')">
          <template #prefix>
            <icon-lucide:shield-check />
          </template>
        </NInput>
      </NFormItem>
      <div class="register-form-tip mb-4">
        {{ $t('page.login.register.inviteCodeTip') }}
      </div>
      <NSpace vertical :size="18" class="w-full">
        <NButton type="primary" size="large" round block :loading="loading" @click="handleSubmit">
          {{ $t('page.login.common.register') }}
        </NButton>
        <NButton block @click="toggleLoginModule('pwd-login')">
          {{ $t('page.login.common.back') }}
        </NButton>
      </NSpace>

      <div class="mt-4 text-center">
        {{ $t('page.login.register.agreement') }}
        <NButton text type="primary">{{ $t('page.login.register.protocol') }}</NButton>
        {{ $t('page.login.register.and') }}
        <NButton text type="primary">{{ $t('page.login.register.policy') }}</NButton>
      </div>
    </NForm>
  </div>
</template>

<style scoped>
.register-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(280px, 0.92fr);
  gap: 20px;
  align-items: stretch;
}

.register-form-panel {
  min-width: 0;
}

.register-form-tip {
  font-size: 14px;
  line-height: 1.7;
  /* R9 quieter 复审：归入唯一的次级文字档 --app-text-secondary（原 0.66 字面墨透明度是第四档杂档） */
  color: var(--app-text-secondary);
}

/* Flat tinted zone inside the airy page: fill-subtle surface, md radius, no border, no shadow */
.invite-side-panel {
  display: flex;
  min-width: 0;
  flex-direction: column;
  border-radius: var(--app-radius-md);
  padding: 24px;
  background: var(--app-fill-subtle);
}

.invite-side-title {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  color: rgb(var(--base-text-color));
}

.invite-side-desc {
  margin-top: 12px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--app-text-secondary);
}

.invite-emphasis,
.invite-keyword-mark {
  display: inline-flex;
  align-items: center;
  border-radius: 9999px;
  padding: 2px 8px;
  font-weight: 500;
  line-height: 1.4;
  color: rgb(var(--base-text-color));
  background: rgb(var(--container-bg-color));
}

.invite-keyword-mark {
  font-family: ui-monospace, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
}

.invite-step-copy {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.invite-inline-copy {
  font-size: 12px;
}

.invite-side-steps {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.invite-step-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--app-text-secondary);
}

.invite-side-bottom {
  margin-top: auto;
  padding-top: 18px;
}

.invite-qr-shell {
  width: min(180px, 52%);
  overflow: hidden;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-md);
  background: rgb(var(--container-bg-color));
}

.invite-qr-image {
  display: block;
  width: 100%;
  aspect-ratio: 1 / 1;
  object-fit: cover;
}

.invite-qr-placeholder {
  display: flex;
  aspect-ratio: 1 / 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px;
  text-align: center;
}

.invite-qr-icon {
  font-size: 42px;
  color: rgb(var(--primary-color));
}

.invite-qr-title {
  margin-top: 12px;
  font-size: 14px;
  font-weight: 600;
  color: rgb(var(--base-text-color));
}

.invite-qr-desc {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--app-text-secondary);
}

.invite-channel-step {
  display: inline-flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-secondary);
  background: rgb(var(--container-bg-color));
}

@media (max-width: 900px) {
  .register-layout {
    grid-template-columns: 1fr;
  }
}
</style>
