<script lang="ts" setup>
import { computed, ref, shallowRef } from 'vue';
import { useRouter } from 'vue-router';
import { onKeyStroke, useDebounceFn } from '@vueuse/core';
import { useRouteStore } from '@/store/modules/route';
import { useAppStore } from '@/store/modules/app';
import { $t } from '@/locales';
import SearchResult from './search-result.vue';
import SearchFooter from './search-footer.vue';

defineOptions({ name: 'SearchModal' });

const router = useRouter();
const appStore = useAppStore();
const routeStore = useRouteStore();

const isMobile = computed(() => appStore.isMobile);

const keyword = ref('');
const activePath = ref('');
const resultOptions = shallowRef<App.Global.Menu[]>([]);

const handleSearch = useDebounceFn(search, 300);

const visible = defineModel<boolean>('show', { required: true });

/**
 * Command palette: overlay material + elevation-3 (DESIGN.md › Material Vocabulary / Shadow Vocabulary). R12 overdrive:
 * the palette is the top of the glass ladder — it keeps the full refraction tier (--app-glass-filter-overlay, R8's
 * 通透档); the anisotropic rim arrives via the .top-50px ring in global.scss.
 */
const commandPaletteStyle = {
  '--n-color': 'var(--app-material-overlay)',
  WebkitBackdropFilter: 'var(--app-glass-filter-overlay)',
  backdropFilter: 'var(--app-glass-filter-overlay)'
};

function search() {
  resultOptions.value = routeStore.searchMenus.filter(menu => {
    const trimKeyword = keyword.value.toLocaleLowerCase().trim();
    const title = (menu.i18nKey ? $t(menu.i18nKey) : menu.label).toLocaleLowerCase();
    return trimKeyword && title.includes(trimKeyword);
  });
  activePath.value = resultOptions.value[0]?.routePath ?? '';
}

function handleClose() {
  // handle with setTimeout to prevent user from seeing some operations
  setTimeout(() => {
    visible.value = false;
    resultOptions.value = [];
    keyword.value = '';
  }, 200);
}

/** key up */
function handleUp() {
  const { length } = resultOptions.value;
  if (length === 0) return;

  const index = getActivePathIndex();
  if (index === -1) return;

  const activeIndex = index === 0 ? length - 1 : index - 1;

  activePath.value = resultOptions.value[activeIndex].routePath;
}

/** key down */
function handleDown() {
  const { length } = resultOptions.value;
  if (length === 0) return;

  const index = getActivePathIndex();
  if (index === -1) return;

  const activeIndex = index === length - 1 ? 0 : index + 1;

  activePath.value = resultOptions.value[activeIndex].routePath;
}

function getActivePathIndex() {
  return resultOptions.value.findIndex(item => item.routePath === activePath.value);
}

/** key enter */
function handleEnter() {
  if (resultOptions.value?.length === 0 || activePath.value === '') return;
  handleClose();
  router.push(activePath.value);
}

function registerShortcut() {
  onKeyStroke('Escape', handleClose);
  onKeyStroke('Enter', handleEnter);
  onKeyStroke('ArrowUp', handleUp);
  onKeyStroke('ArrowDown', handleDown);
}

registerShortcut();
</script>

<template>
  <NModal
    v-model:show="visible"
    :segmented="{ footer: 'soft' }"
    :closable="false"
    preset="card"
    auto-focus
    footer-style="padding: 0; margin: 0"
    class="fixed left-0 right-0 shadow-[var(--app-shadow-e3)]"
    :style="commandPaletteStyle"
    :class="[isMobile ? 'size-full top-0px rounded-0' : 'w-630px top-50px rounded-24px']"
    @after-leave="handleClose"
  >
    <NInputGroup>
      <NInput v-model:value="keyword" clearable :placeholder="$t('common.keywordSearch')" @input="handleSearch">
        <template #prefix>
          <icon-lucide:search class="text-icon-small text-tertiary" />
        </template>
      </NInput>
      <NButton v-if="isMobile" type="primary" ghost @click="handleClose">{{ $t('common.cancel') }}</NButton>
    </NInputGroup>

    <div class="mt-20px">
      <!--
 R11 delight 复审：palette 打开时 resultOptions 初始为空，未输入即闪默认灰盒插画——
           玻璃命令面板的空态只留一行安静文字（show-icon=false，Naive 官方纯文字空态配置），
           灰盒插画从旗舰玻璃面退役；文案 common.noData 原样保留 
-->
      <NEmpty v-if="resultOptions.length === 0" :show-icon="false" :description="$t('common.noData')" />
      <SearchResult v-else v-model:path="activePath" :options="resultOptions" @enter="handleEnter" />
    </div>
    <template #footer>
      <SearchFooter v-if="!isMobile" />
    </template>
  </NModal>
</template>

<style lang="scss" scoped></style>
