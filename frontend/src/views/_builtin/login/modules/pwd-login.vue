<script setup lang="ts">
import { reactive } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { isRequestRateLimited, requestRateLimitRemainingSeconds } from '@/service/request/rate-limit';
import { useAuthStore } from '@/store/modules/auth';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { localStg } from '@/utils/storage';
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

const rememberedLogin = localStg.get('rememberedLogin');

// One-time migration: legacy rememberedLogin may contain a plaintext password;
// strip it and rewrite in place so old users don't keep plaintext in localStorage.
if (rememberedLogin && 'password' in rememberedLogin) {
  localStg.set('rememberedLogin', { userName: rememberedLogin.userName });
}

const model: FormModel = reactive({
  userName: rememberedLogin?.userName || '',
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
  </NForm>
</template>

<style scoped></style>
