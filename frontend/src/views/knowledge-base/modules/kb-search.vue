<script setup lang="ts">
defineOptions({
  name: 'KbSearch'
});

const emit = defineEmits<{
  search: [];
  reset: [];
}>();

defineProps<{ loading: boolean }>();

const model = defineModel<Api.KnowledgeBase.FilterDraft>('model', { required: true });

/** 状态筛选口径与表格展示列一致：上传状态列（处理中/已完成）+ 实际向量化列的失败态（pm-024a） */
const statusOptions = [
  { label: '处理中', value: 'UPLOADING' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '向量化失败', value: 'VECTOR_FAILED' }
];

/** NSelect 值不支持 boolean，公开/私有以字符串承载，查询时在视图内换算 */
const visibilityOptions = [
  { label: '公开', value: 'PUBLIC' },
  { label: '私有', value: 'PRIVATE' }
];
</script>

<template>
  <NForm
    class="page-filters"
    :model="model"
    label-placement="left"
    :show-feedback="false"
    inline
    @submit.prevent="emit('search')"
  >
    <NFormItem label="文件名" path="fileName">
      <NInput v-model:value="model.fileName" placeholder="请输入文件名关键词" clearable class="w-220px!" />
    </NFormItem>
    <NFormItem label="是否公开" path="isPublic">
      <NSelect
        v-model:value="model.isPublic"
        placeholder="全部"
        :options="visibilityOptions"
        clearable
        class="w-140px!"
      />
    </NFormItem>
    <NFormItem label="状态" path="status">
      <NSelect
        v-model:value="model.status"
        placeholder="全部状态"
        :options="statusOptions"
        clearable
        class="w-160px!"
      />
    </NFormItem>
    <div class="filter-actions">
      <NButton type="primary" attr-type="submit" :loading="loading">查询</NButton>
      <NButton :disabled="loading" @click="emit('reset')">重置</NButton>
    </div>
  </NForm>
</template>

<style scoped></style>
