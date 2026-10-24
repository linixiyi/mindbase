<script lang="ts" setup>
import { $t } from '@/locales';

defineOptions({ name: 'SearchFooter' });
</script>

<template>
  <div class="h-44px flex-y-center gap-14px px-24px">
    <span class="flex-y-center">
      <icon-lucide:corner-down-left class="operate-shadow operate-item" />
      <span>{{ $t('common.confirm') }}</span>
    </span>
    <span class="flex-y-center">
      <icon-lucide:arrow-up class="operate-shadow operate-item" />
      <icon-lucide:arrow-down class="operate-shadow operate-item" />
      <span>{{ $t('common.switch') }}</span>
    </span>
    <span class="flex-y-center">
      <!-- lucide 无 esc 字形；ESC 用文字键帽（命令面板通行式样），与图标键帽同材质 -->
      <span class="operate-shadow operate-key">ESC</span>
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
  --uno: mr-6px p-2px text-icon;
}

/* ESC 文字键帽：与图标键帽同高（12px Caption + 2px 上下内边距） */
.operate-key {
  --uno: mr-6px px-6px py-2px text-12px font-500;
}
</style>
