<script setup lang="ts">
defineOptions({
  name: 'SearchDialog'
});

const emit = defineEmits<{
  preview: [fileName: string, fileMd5: string];
}>();

const loading = ref(false);
const visible = defineModel<boolean>('visible', { default: false });

const { formRef, restoreValidation } = useNaiveForm();

const store = useAuthStore();
const model = ref<Api.KnowledgeBase.SearchParams>(createDefaultModel());

function createDefaultModel(): Api.KnowledgeBase.SearchParams {
  return {
    userId: `${store.userInfo.id}`,
    query: '',
    topK: 10
  };
}

const list = ref<Api.KnowledgeBase.SearchResult[]>([]);
const searched = ref(false);

const patterns = ref<string[]>([]);
function highlight(text: string) {
  if (!model.value.query) return false;
  if (text.includes(model.value.query)) return true;
  return false;
}

async function search() {
  loading.value = true;
  const { error, data } = await request<Api.KnowledgeBase.SearchResult[]>({
    url: 'search/hybrid',
    params: model.value
  });
  if (!error) {
    list.value = data;
    patterns.value = [model.value.query];
  }
  searched.value = true;
  loading.value = false;
}

function reset() {
  model.value = createDefaultModel();
  patterns.value = [];
  list.value = [];
  searched.value = false;
  restoreValidation();
}
watch(visible, () => {
  if (visible.value) {
    reset();
  }
});
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="知识库检索"
    :show-icon="false"
    :mask-closable="false"
    class="w-1000px!"
  >
    <NForm
      ref="formRef"
      :model="model"
      label-placement="left"
      :label-width="60"
      inline
      class="pb-2"
      :show-feedback="false"
    >
      <NGrid>
        <NFormItemGi label="返回条数" path="topK" class="pr-24px" span="6">
          <NInputNumber
            v-model:value="model.topK"
            placeholder="最多返回的结果数"
            clearable
            :min="1"
            :precision="0"
            :step="10"
          />
        </NFormItemGi>
        <NFormItemGi label="关键字" path="query" class="pr-24px" span="12">
          <NInput v-model:value="model.query" placeholder="请输入关键字" clearable />
        </NFormItemGi>
        <NFormItemGi span="6">
          <NSpace class="w-full" justify="end">
            <NButton @click="reset">
              <template #icon>
                <icon-ic-round-refresh class="text-icon" />
              </template>
              重置
            </NButton>
            <NButton type="primary" @click="search">
              <template #icon>
                <icon-ic-round-search class="text-icon" />
              </template>
              搜索
            </NButton>
          </NSpace>
        </NFormItemGi>
      </NGrid>
    </NForm>
    <NSpin :show="loading">
      <div v-if="list.length === 0" class="flex flex-col items-center justify-center gap-4 py-100px">
        <div class="h-20 w-20 flex items-center justify-center rounded-2xl bg-[rgb(var(--primary-color)/0.08)]">
          <icon-ic-round-search v-if="!searched" class="text-36px text-[rgb(var(--primary-color)/0.5)]" />
          <icon-ic-round-search-off v-else class="text-36px text-[rgb(var(--primary-color)/0.5)]" />
        </div>
        <div class="text-body text-15px font-500">{{ searched ? '未找到匹配内容' : '输入关键字检索知识库' }}</div>
        <div class="text-muted text-12px">{{ searched ? '换个关键字试试' : '输入关键字后点击「搜索」开始检索' }}</div>
      </div>
      <NScrollbar v-else class="max-h-500px">
        <div class="flex flex-col gap-12px">
          <div v-for="(item, index) in list" :key="index" class="search-result-item">
            <div class="mb-8px flex items-center justify-between gap-12px text-12px leading-18px">
              <button
                type="button"
                class="source-file-link min-w-0 truncate text-left"
                :title="item.fileName"
                @click="emit('preview', item.fileName, item.fileMd5)"
              >
                来源：{{ item.fileName }}
              </button>
              <NTag :bordered="false" class="score-tag shrink-0">
                Score
                <span class="ml-4px font-mono">{{ item.score }}</span>
              </NTag>
            </div>
            <div class="text-14px leading-24px">
              <NHighlight
                v-if="highlight(item.textContent)"
                highlight-class="bg-[rgb(var(--primary-color)/0.15)] color-[#3634b3] rd-4px dark:color-[#8c96ff]"
                :text="item.textContent"
                :patterns="patterns"
              />
              <span v-else>{{ item.textContent }}</span>
            </div>
          </div>
        </div>
      </NScrollbar>
    </NSpin>
  </NModal>
</template>

<style scoped lang="scss">
.search-result-item {
  padding: 12px 16px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  background: var(--app-subtle);
}

.score-tag {
  background: rgb(var(--primary-color) / 0.12);
  color: #342cd8;
}

html.dark .score-tag {
  color: #8c96ff;
}

.source-file-link {
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  color: #1890ff;
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: #40a9ff;
    text-decoration: none;
  }

  &:active {
    color: #096dd9;
  }

  &:focus-visible {
    outline: 2px solid rgb(var(--primary-color) / 0.38);
    outline-offset: 2px;
  }
}
</style>
