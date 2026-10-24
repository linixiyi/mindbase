<script setup lang="ts">
defineOptions({
  name: 'SearchDialog'
});

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

const emptyDescription = computed(() =>
  searched.value ? '未检索到相关内容，可换个关键字或调整 topK 后重试' : '输入关键字，检索知识库中的内容'
);

async function search() {
  loading.value = true;
  const { error, data } = await request<Api.KnowledgeBase.SearchResult[]>({
    url: 'search/hybrid',
    params: model.value
  });
  if (!error) {
    list.value = data;
    patterns.value = [model.value.query];
    searched.value = true;
  } else {
    window.$message?.error('检索失败，请稍后重试');
  }
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
        <NFormItemGi label="topK" path="topK" class="pr-24px" span="6">
          <NInputNumber
            v-model:value="model.topK"
            placeholder="请输入topK"
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
                <icon-lucide:rotate-cw class="text-icon" />
              </template>
              重置
            </NButton>
            <NButton type="primary" @click="search">
              <template #icon>
                <icon-lucide:search class="text-icon" />
              </template>
              搜索
            </NButton>
          </NSpace>
        </NFormItemGi>
      </NGrid>
    </NForm>
    <NSpin :show="loading">
      <!--
 R11 delight 复审：旗舰检索入口的空态从默认 NEmpty 灰盒升为 azure 徽标 tile
           （.app-state-badge 家族，实底对话框身体上的静态 tile）；教学文案 emptyDescription 原样保留 
-->
      <div v-if="list.length === 0" class="search-empty">
        <div class="app-state-badge">
          <icon-lucide:file-search class="text-icon-large" />
        </div>
        <p class="search-empty__desc">{{ emptyDescription }}</p>
      </div>
      <NScrollbar v-else class="max-h-500px">
        <NCard
          v-for="(item, index) in list"
          :key="index"
          class="my-8"
          embedded
          :segmented="{
            content: true,
            footer: 'soft'
          }"
        >
          <div>
            <NHighlight
              v-if="highlight(item.textContent)"
              highlight-class="rounded-full bg-[rgb(var(--primary-color)/0.1)] dark:bg-[rgb(var(--primary-color)/0.16)] text-[rgb(var(--primary-color))] dark:text-[rgb(var(--primary-400-color))] px-2 mx-1"
              :text="item.textContent"
              :patterns="patterns"
            />
            <span v-else>{{ item.textContent }}</span>
          </div>
          <template #footer>
            <div class="flex items-center justify-between gap-12px">
              <span class="text-muted text-14px">来源：{{ item.fileName }}</span>
              <NTag :bordered="false" size="small" class="text-12px tabular-nums">Score: {{ item.score }}</NTag>
            </div>
          </template>
        </NCard>
      </NScrollbar>
    </NSpin>
  </NModal>
</template>

<style scoped lang="scss">
/* R11 delight 复审：与 .table-state 家族同律（徽标 tile 经 global.scss 单源供给；
   描述 14/1.55 次级墨、一次性 250ms standard 入场——全部家族属性） */
.search-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 24px;
  text-align: center;
  animation: search-empty-in var(--app-dur-base) var(--app-ease-standard);
}

.search-empty__desc {
  margin: 8px 0 0;
  max-width: 360px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--app-muted);
}

@keyframes search-empty-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
