<script lang="ts" setup>
import { $t } from '@/locales';

defineOptions({ name: 'SearchFooter' });
</script>

<template>
  <div class="h-44px flex-y-center gap-14px px-24px">
    <span class="flex-y-center">
      <icon-mdi-keyboard-return class="operate-shadow operate-item" />
      <span>{{ $t('common.confirm') }}</span>
    </span>
    <span class="flex-y-center">
      <icon-mdi-arrow-up-thin class="operate-shadow operate-item" />
      <icon-mdi-arrow-down-thin class="operate-shadow operate-item" />
      <span>{{ $t('common.switch') }}</span>
    </span>
    <span class="flex-y-center">
      <icon-mdi-keyboard-esc class="operate-shadow operate-item" />
      <span>{{ $t('common.close') }}</span>
    </span>
  </div>
</template>

<style lang="scss" scoped>
/* Keycap styling, token-driven (was hardcoded hex) */
.operate-shadow {
  background: var(--app-fill-subtle);
  border-radius: 4px;
  box-shadow: inset 0 -2px var(--app-fill-hover);
}

.operate-item {
  --uno: mr-6px p-2px text-20px;
}
</style>
