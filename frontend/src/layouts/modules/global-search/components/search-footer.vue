<script lang="ts" setup>
import { $t } from '@/locales';

defineOptions({ name: 'SearchFooter' });
</script>

<template>
  <div class="h-44px flex-y-center gap-14px px-24px">
    <span class="flex-y-center">
      <icon-mdi-keyboard-return class="keycap operate-item" />
      <span>{{ $t('common.confirm') }}</span>
    </span>
    <span class="flex-y-center">
      <icon-mdi-arrow-up-thin class="keycap operate-item" />
      <icon-mdi-arrow-down-thin class="keycap operate-item" />
      <span>{{ $t('common.switch') }}</span>
    </span>
    <span class="flex-y-center">
      <icon-mdi-keyboard-esc class="keycap operate-item" />
      <span>{{ $t('common.close') }}</span>
    </span>
  </div>
</template>

<style lang="scss" scoped>
.keycap {
  --uno: rounded-4px shadow-sm dark: shadow-none;
  border: 1px solid var(--app-border);
}

.operate-item {
  --uno: mr-6px p-2px text-20px;
}
</style>
