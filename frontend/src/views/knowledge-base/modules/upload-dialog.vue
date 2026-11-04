<script setup lang="ts">
import { uploadAccept } from '@/constants/common';
import { UploadStatus } from '@/enum';

defineOptions({
  name: 'UploadDialog'
});

const loading = ref(false);
const visible = defineModel<boolean>('visible', { default: false });
const orgOptions = ref<Api.OrgTag.Details[]>([]);
const orgLoading = ref(false);
const orgLoadFailed = ref(false);
let orgRequestId = 0;

const authStore = useAuthStore();

/** 本次提交的上传任务（pm-019 过程条 × pm-024c 多文件）：保存后对话框内逐文件展示当前阶段，失败可单文件重试 */
const submittedTaskMd5s = ref<string[]>([]);
const submitted = computed(() => submittedTaskMd5s.value.length > 0);

const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const model = ref<Api.KnowledgeBase.Form>(createDefaultModel());

function createDefaultModel(): Api.KnowledgeBase.Form {
  return {
    orgTag: null,
    orgTagName: '',
    uploadMaxSizeBytes: null,
    uploadMaxSizeMb: null,
    isPublic: false,
    fileList: []
  };
}

const rules = ref<FormRules>({
  orgTag: defaultRequiredRule,
  isPublic: defaultRequiredRule,
  fileList: defaultRequiredRule
});

const fileSizeLimitError = computed(() => {
  if (authStore.isAdmin) return '';
  if (!model.value.uploadMaxSizeBytes) return '';
  // pm-024c：多文件逐个校验组织大小上限，超限文件点名提示
  const oversized = (model.value.fileList || [])
    .filter(item => item.file && item.file.size > model.value.uploadMaxSizeBytes!)
    .map(item => item.file!.name);
  if (!oversized.length) return '';

  return `以下文件超过组织限制 ${model.value.uploadMaxSizeMb} MB：${oversized.join('、')}`;
});

const submitDisabled = computed(
  () => loading.value || orgLoading.value || !model.value.orgTag || Boolean(fileSizeLimitError.value)
);

function close() {
  visible.value = false;
}

const store = useKnowledgeBaseStore();
const { tasks } = storeToRefs(store);
/** 本次提交涉及的任务（pm-024c：多文件逐文件展示，随任务状态实时刷新） */
const submittedTasks = computed(() =>
  submittedTaskMd5s.value
    .map(md5 => tasks.value.find(task => task.fileMd5 === md5))
    .filter((task): task is Api.KnowledgeBase.UploadTask => Boolean(task))
);

type SubmittedStage = {
  type: 'default' | 'success' | 'error';
  label: string;
  failed?: boolean;
  hint?: string;
};

function submittedStageOf(task: Api.KnowledgeBase.UploadTask): SubmittedStage | null {
  if (task.status === UploadStatus.Break) {
    return { type: 'error', label: '上传失败', failed: true };
  }
  if (task.status === UploadStatus.Completed) {
    if (task.vectorizationStatus === 'PENDING' || task.vectorizationStatus === 'PROCESSING') {
      return { type: 'success', label: '已完成', hint: '向量化处理中，完成后可在文件列表查看实际 Tokens' };
    }
    if (task.vectorizationStatus === 'FAILED') {
      return { type: 'success', label: '已完成', hint: '向量化失败，可在文件列表重试向量化' };
    }
    return { type: 'success', label: '已完成' };
  }
  if (task.status === UploadStatus.Uploading) return null;
  return { type: 'default', label: '排队中' };
}

function retrySubmittedUpload(task: Api.KnowledgeBase.UploadTask) {
  // 服务端恢复的断点任务可能没有本地 File（store 入队时已按 MD5 回填），此处兜底按文件名匹配本次表单文件
  if (!task.file) {
    const formFile = model.value.fileList?.find(item => item.file?.name === task.fileName)?.file;
    if (formFile) {
      task.file = formFile;
    }
  }
  task.status = UploadStatus.Pending;
  store.startUpload();
}

async function handleSubmit() {
  await validate();
  if (fileSizeLimitError.value) return;

  loading.value = true;
  try {
    const involvedTasks = await store.enqueueUpload(model.value);
    if (involvedTasks.length) {
      // 提交成功：对话框逐文件展示实时阶段（多文件各自独立成败态）
      submittedTaskMd5s.value = involvedTasks.map(task => task.fileMd5);
    } else {
      // 重复文件/正在上传中已由 store 给出错误提示，维持原行为关闭
      close();
    }
  } catch (error) {
    // MD5 读取等本地异常：给出可见反馈，loading 在 finally 中复位，不留静默失败
    window.$message?.error(error instanceof Error ? error.message : '文件校验失败，请重试');
  } finally {
    loading.value = false;
  }
}

async function loadUploadOrganizations() {
  orgRequestId += 1;
  const requestId = orgRequestId;
  orgOptions.value = [];
  orgLoading.value = true;
  orgLoadFailed.value = false;
  const { error, data } = await request<Api.OrgTag.Mine>({ url: '/users/org-tags' });
  if (requestId !== orgRequestId || !visible.value) return;

  orgLoading.value = false;
  orgLoadFailed.value = Boolean(error);
  if (error || !data) return;

  orgOptions.value = data.orgTagDetails || [];
  const preferredOrg = orgOptions.value.find(org => org.tagId === data.primaryOrg) || orgOptions.value[0];
  if (preferredOrg) {
    model.value.orgTag = preferredOrg.tagId;
    onUpdate(preferredOrg);
  }
}

watch(visible, () => {
  if (visible.value) {
    model.value = createDefaultModel();
    submittedTaskMd5s.value = [];
    loadUploadOrganizations();
    restoreValidation();
  } else {
    orgRequestId += 1;
  }
});

function onUpdate(option: unknown) {
  if (option) {
    const selected = option as Api.OrgTag.Item;
    model.value.orgTagName = selected.name;
    model.value.uploadMaxSizeBytes = selected.uploadMaxSizeBytes;
    model.value.uploadMaxSizeMb = selected.uploadMaxSizeMb;
    return;
  }
  model.value.orgTagName = '';
  model.value.uploadMaxSizeBytes = null;
  model.value.uploadMaxSizeMb = null;
}
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="文件上传"
    :show-icon="false"
    :mask-closable="false"
    class="w-500px!"
    @positive-click="handleSubmit"
  >
    <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" :label-width="100" mt-10>
      <NFormItem label="组织标签" path="orgTag">
        <div class="w-full">
          <NSelect
            v-model:value="model.orgTag"
            :options="orgOptions"
            label-field="name"
            value-field="tagId"
            placeholder="请选择组织标签"
            :loading="orgLoading"
            :disabled="orgLoading || orgOptions.length <= 1"
            filterable
            @update:value="(_, option) => onUpdate(option)"
          />
          <div class="text-muted mt-8px text-12px">仅可选择自己所属的组织标签。</div>
          <div v-if="orgLoadFailed" class="form-hint--error mt-8px text-12px">组织标签列表加载失败，请关闭后重试。</div>
          <div v-else-if="!orgLoading && !orgOptions.length" class="form-hint--error mt-8px text-12px">
            暂无可选的组织标签，请先配置组织成员关系。
          </div>
        </div>
      </NFormItem>

      <NFormItem label="是否公开" path="isPublic">
        <NRadioGroup v-model:value="model.isPublic" name="radiogroup">
          <NSpace :size="16">
            <NRadio :value="true">公开</NRadio>
            <NRadio :value="false">私有</NRadio>
          </NSpace>
        </NRadioGroup>
      </NFormItem>
      <NFormItem label="上传文件" path="fileList">
        <NUpload v-model:file-list="model.fileList" :accept="uploadAccept" multiple :default-upload="false">
          <NButton>上传文件</NButton>
        </NUpload>
        <!-- pm-024c：支持一次多选文件，逐个入队，每个文件独立进度与成败态 -->
        <div v-if="!model.fileList?.length" class="text-muted mt-8px text-12px">支持多选文件，将逐个入队上传。</div>
        <div v-if="fileSizeLimitError" class="form-hint--error mt-8px text-12px">
          {{ fileSizeLimitError }}
        </div>
        <div v-else-if="!authStore.isAdmin && model.uploadMaxSizeMb" class="form-hint--warning mt-8px text-12px">
          当前组织限制非管理员上传文件不超过 {{ model.uploadMaxSizeMb }} MB
        </div>
      </NFormItem>
    </NForm>

    <!-- pm-019 过程感：哈希阶段的过程文案（多文件逐个计算 MD5） -->
    <div v-if="loading" class="text-muted mt-8px text-12px">正在计算文件指纹（MD5），大文件可能需要数秒…</div>

    <!-- pm-019 过程条 × pm-024c：保存后逐文件展示当前阶段（排队中/分片中/已完成/失败），失败可单文件重试 -->
    <template v-if="submittedTasks.length">
      <div v-for="task in submittedTasks" :key="task.fileMd5" class="mt-12px">
        <div class="flex items-center gap-8px">
          <span class="min-w-0 flex-1 truncate text-13px" :title="task.fileName">
            {{ task.fileName }}
          </span>
          <template v-if="task.status === UploadStatus.Uploading">
            <span class="text-muted whitespace-nowrap text-12px">分片中</span>
            <NProgress
              class="min-w-0 w-160px!"
              type="line"
              :percentage="Math.min(100, Math.round(task.progress))"
              processing
              :show-indicator="true"
            />
          </template>
          <NTag v-else-if="submittedStageOf(task)" :type="submittedStageOf(task)!.type" size="small" :bordered="false">
            {{ submittedStageOf(task)!.label }}
          </NTag>
          <NButton
            v-if="submittedStageOf(task)?.failed"
            size="tiny"
            type="error"
            quaternary
            @click="retrySubmittedUpload(task)"
          >
            重试
          </NButton>
        </div>
        <div v-if="submittedStageOf(task)?.hint" class="text-muted mt-4px text-12px">
          {{ submittedStageOf(task)?.hint }}
        </div>
      </div>
    </template>

    <template #action>
      <NSpace :size="16">
        <template v-if="submitted">
          <NButton @click="close">关闭</NButton>
        </template>
        <template v-else>
          <NButton @click="close">取消</NButton>
          <NButton type="primary" :loading="loading" :disabled="submitDisabled" @click="handleSubmit">保存</NButton>
        </template>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped lang="scss">
.form-hint--error {
  color: rgb(var(--error-700-color));
}

.form-hint--warning {
  color: rgb(var(--warning-700-color));
}

html.dark .form-hint--error {
  color: rgb(var(--error-400-color));
}

html.dark .form-hint--warning {
  color: rgb(var(--warning-400-color));
}
</style>
