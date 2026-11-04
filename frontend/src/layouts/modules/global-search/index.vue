<script lang="ts" setup>
import { onKeyStroke } from '@vueuse/core';
import { useBoolean } from '@sa/hooks';
import { $t } from '@/locales';
import SearchModal from './components/search-modal.vue';

defineOptions({ name: 'GlobalSearch' });

const { bool: show, toggle } = useBoolean();

/**
 * pm-014：全局 Cmd/Ctrl+K 打开命令面板。原生动件输入框（input/textarea/contentEditable）不抢占， 保证用户正在打字时快捷键只对非输入场景生效；面板内部的
 * ESC/Enter/箭头键位不受影响。
 */
onKeyStroke(
  'k',
  event => {
    // 仅响应 Cmd/Ctrl 组合键：裸按 k 不触发（macOS 用 metaKey，Windows/Linux 用 ctrlKey）
    if (!(event.ctrlKey || event.metaKey)) return;
    const target = event.target as HTMLElement | null;
    const isEditable =
      target?.isContentEditable ||
      target?.tagName === 'INPUT' ||
      target?.tagName === 'TEXTAREA' ||
      target?.tagName === 'SELECT';
    if (isEditable) return;
    event.preventDefault();
    toggle();
  },
  { dedupe: true }
);
</script>

<template>
  <!--
 pm-014：触发钮以文字键帽标注全局快捷键（键帽材质复用 search-footer 的 operate-key 家族）。
       ButtonIcon 只有默认插槽（button-icon.vue:38）——键帽放默认插槽随图标常显，悬停 tooltip 仍是 common.search 
-->
  <ButtonIcon :tooltip-content="$t('common.search')">
    <span class="global-search-trigger" @click="toggle">
      <icon-lucide:search />
      <span class="global-search-keycap">Ctrl K</span>
    </span>
  </ButtonIcon>
  <SearchModal v-model:show="show" />
</template>

<style lang="scss" scoped>
.global-search-trigger {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

/* 与 search-footer 的 .operate-shadow / .operate-key 同材质：token 化键帽，命令面板通行式样 */
.global-search-keycap {
  padding: 1px 4px;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.2;
  background: var(--app-fill-subtle);
  border-radius: 4px;
  box-shadow: inset 0 -2px var(--app-fill-hover);
}
</style>
