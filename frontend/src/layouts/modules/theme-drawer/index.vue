<script setup lang="ts">
import { useAppStore } from '@/store/modules/app';
import { $t } from '@/locales';
import DarkMode from './modules/dark-mode.vue';
import LayoutMode from './modules/layout-mode.vue';
import ThemeColor from './modules/theme-color.vue';
import PageFun from './modules/page-fun.vue';
import ConfigOperation from './modules/config-operation.vue';

defineOptions({
  name: 'ThemeDrawer'
});

const appStore = useAppStore();
</script>

<template>
  <!--
    theme-drawer 类把面板从通栏贴边改为浮岛姿态（global.scss › R5 liquid glass / DESIGN.md §12）：
    距视口上/右/下各 12px、四角 24px、elevation-2 软影 + rim light；表面保持实底（正文永远实底）。
  -->
  <NDrawer v-model:show="appStore.themeDrawerVisible" display-directive="show" :width="360" class="theme-drawer">
    <NDrawerContent :title="$t('theme.themeDrawerTitle')" :native-scrollbar="false" closable>
      <DarkMode />
      <LayoutMode />
      <ThemeColor />
      <PageFun />
      <template #footer>
        <ConfigOperation />
      </template>
    </NDrawerContent>
  </NDrawer>
</template>
