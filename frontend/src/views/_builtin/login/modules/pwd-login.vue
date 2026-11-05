<script setup lang="ts">
import { reactive } from 'vue';
import { REGISTER_USERNAME_PREFILL_KEY, loginModuleRecord } from '@/constants/app';
import { isRequestRateLimited, requestRateLimitRemainingSeconds } from '@/service/request/rate-limit';
import { useAuthStore } from '@/store/modules/auth';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { localStg, migrateRememberedLogin } from '@/utils/storage';
import { $t } from '@/locales';

defineOptions({
  name: 'PwdLogin'
});

const authStore = useAuthStore();
const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();

interface FormModel {
  userName: string;
  password: string;
  rememberMe: boolean;
}

type FormRuleModel = Pick<FormModel, 'userName' | 'password'>;

// 旧版 rememberedLogin 的明文密码迁移（pm-002）：逻辑单源于 utils/storage.ts，应用启动时也会执行
const rememberedLogin = migrateRememberedLogin();

// pm-032 注册成功跳转回填：一次性消费（读后即删），优先于「记住我」的回填
const registeredPrefill = sessionStorage.getItem(REGISTER_USERNAME_PREFILL_KEY);
if (registeredPrefill) {
  sessionStorage.removeItem(REGISTER_USERNAME_PREFILL_KEY);
}

const model: FormModel = reactive({
  userName: registeredPrefill || rememberedLogin?.userName || '',
  password: '',
  rememberMe: Boolean(rememberedLogin)
});

const rules = computed<Record<keyof FormRuleModel, App.Global.FormRule[]>>(() => {
  // inside computed to make locale reactive, if not apply i18n, you can define it without computed
  const { formRules } = useFormRules();

  return {
    userName: formRules.userName,
    password: formRules.pwd
  };
});

// pm-032 忘记密码：本站无邮件/自助重置基础设施，不做假重置表单（reset-pwd 模块为静态占位），
// 只做诚实引导——提示联系管理员重置，管理员可在「用户管理」查看账号
const showForgotPassword = ref(false);

async function handleSubmit() {
  // 登录限流倒计时（pm-022）：冷却期内禁止提交，按钮展示剩余秒数
  if (isRequestRateLimited.value) {
    window.$message?.warning(`发送过于频繁，${requestRateLimitRemainingSeconds.value} 秒后可重试`);
    return;
  }

  await validate();

  const success = await authStore.login(model.userName, model.password);

  if (!success) {
    return;
  }

  if (model.rememberMe) {
    // Only persist the user name; the password must never be written to client storage.
    localStg.set('rememberedLogin', {
      userName: model.userName
    });
  } else {
    localStg.remove('rememberedLogin');
  }
}
</script>

<template>
  <NForm ref="formRef" :model="model" :rules="rules" size="large" :show-label="false" @keyup.enter="handleSubmit">
    <NFormItem path="userName">
      <NInput v-model:value="model.userName" :placeholder="$t('page.login.common.userNamePlaceholder')">
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
    <div class="mb-6 flex-y-center justify-between">
      <NCheckbox v-model:checked="model.rememberMe">
        {{ $t('page.login.pwdLogin.rememberMe') }}
      </NCheckbox>
      <NButton text type="primary" class="text-13px" @click="showForgotPassword = true">
        {{ $t('page.login.pwdLogin.forgetPassword') }}
      </NButton>
    </div>
    <div class="flex-col gap-6">
      <NButton
        type="primary"
        size="large"
        block
        :loading="authStore.loginLoading"
        :disabled="isRequestRateLimited"
        @click="handleSubmit"
      >
        {{ isRequestRateLimited ? `${requestRateLimitRemainingSeconds} 秒后可重试` : $t('page.login.common.login') }}
      </NButton>
      <NButton block @click="toggleLoginModule('register')">
        {{ $t(loginModuleRecord.register) }}
      </NButton>

      <span class="text-center">
        登录即代表已阅读并同意我们的
        <NButton text type="primary">用户协议</NButton>
        和
        <NButton text type="primary">隐私政策</NButton>
      </span>
    </div>

    <!-- pm-032 忘记密码引导层：诚实提示联系管理员，不提供假的重置表单 -->
    <NModal
      v-model:show="showForgotPassword"
      preset="card"
      :title="$t('page.login.pwdLogin.forgotPasswordTitle')"
      class="w-380px"
      :bordered="false"
    >
      <div class="forgot-password-tip flex-col gap-8px text-14px leading-relaxed">
        <span>{{ $t('page.login.pwdLogin.forgotPasswordTipLead') }}</span>
        <span>{{ $t('page.login.pwdLogin.forgotPasswordTipBody') }}</span>
      </div>
      <template #footer>
        <NButton type="primary" block @click="showForgotPassword = false">
          {{ $t('page.login.pwdLogin.forgotPasswordConfirm') }}
        </NButton>
      </template>
    </NModal>
  </NForm>
</template>

<style scoped>
.forgot-password-tip {
  color: var(--app-text-secondary);
}
</style>

<style scoped></style>
