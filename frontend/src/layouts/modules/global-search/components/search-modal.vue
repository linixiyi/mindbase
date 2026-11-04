<script lang="ts" setup>
import { computed, ref, shallowRef } from 'vue';
import { useRouter } from 'vue-router';
import { onKeyStroke, useDebounceFn } from '@vueuse/core';
import { request } from '@/service/request';
import { useRouteStore } from '@/store/modules/route';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { $t } from '@/locales';
import { modKeyLabel } from '../keys';
import SearchResult from './search-result.vue';
import SearchFooter from './search-footer.vue';

defineOptions({ name: 'SearchModal' });

const router = useRouter();
const appStore = useAppStore();
const routeStore = useRouteStore();
const authStore = useAuthStore();

const isMobile = computed(() => appStore.isMobile);

const keyword = ref('');
const activePath = ref('');
const resultOptions = shallowRef<App.Global.Menu[]>([]);

/** pm-014：知识库文档命中（复用既有混合检索端点 search/hybrid，零后端改动） */
const docResults = ref<Api.KnowledgeBase.SearchResult[]>([]);
const docLoading = ref(false);
const docSearched = ref(false);

const handleSearch = useDebounceFn(search, 300);

const visible = defineModel<boolean>('show', { required: true });

/** 空关键词不算「已检索」：空态文案区分「尚未检索」与「未命中」，均不带文档分组 */
const showDocSection = computed(() => docResults.value.length > 0);
const showEmpty = computed(() => resultOptions.value.length === 0 && !showDocSection.value);
const emptyDescription = computed(() => {
  if (docLoading.value) return $t('common.searchSearching');
  if (docSearched.value) return $t('common.searchNoResult');
  return $t('common.searchEmptyHint');
});

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
  const trimKeyword = keyword.value.toLocaleLowerCase().trim();
  resultOptions.value = routeStore.searchMenus.filter(menu => {
    const title = (menu.i18nKey ? $t(menu.i18nKey) : menu.label).toLocaleLowerCase();
    return trimKeyword && title.includes(trimKeyword);
  });
  activePath.value = resultOptions.value[0]?.routePath ?? '';
  searchDocs();
}

/** 知识库检索：空关键词不出文档组；失败时文档组清空、菜单命中仍在（错误提示走全局 request onError，与知识库检索对话框一致），面板不打断 */
async function searchDocs() {
  const query = keyword.value.trim();
  if (!query) {
    docResults.value = [];
    docSearched.value = false;
    docLoading.value = false;
    return;
  }
  docLoading.value = true;
  const { error, data } = await request<Api.KnowledgeBase.SearchResult[]>({
    url: 'search/hybrid',
    params: {
      userId: `${authStore.userInfo.id}`,
      query,
      topK: 5
    }
  });
  docLoading.value = false;
  if (error) {
    docResults.value = [];
    docSearched.value = false;
    return;
  }
  docResults.value = data;
  docSearched.value = true;
}

function handleDocClick() {
  // 既有检索对话框只展示命中无定位跳转，按约定回落：跳知识库列表页并携带关键词
  handleClose();
  router.push({ path: '/knowledge-base', query: { q: keyword.value.trim() } });
}

function handleClose() {
  // handle with setTimeout to prevent user from seeing some operations
  setTimeout(() => {
    visible.value = false;
    resultOptions.value = [];
    keyword.value = '';
    docResults.value = [];
    docSearched.value = false;
    docLoading.value = false;
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
  if (resultOptions.value?.length === 0 || activePath.value === '') {
    // pm-014：无菜单命中但有文档命中时，Enter 与点击同语义——跳知识库页
    if (showDocSection.value) handleDocClick();
    return;
  }
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
           灰盒插画从旗舰玻璃面退役 
-->
      <template v-if="showEmpty">
        <NEmpty :show-icon="false" :description="emptyDescription" />
        <!-- pm-014：空态随附面板快捷键提示，键帽复用页脚 operate-key 家族材质；分平台标注与顶栏触发钮同源取 keys.ts（macOS 显示 ⌘ K，不渲染 + 号） -->
        <p v-if="!isMobile" class="palette-empty-shortcut">
          <span class="palette-keycap">{{ modKeyLabel }}</span>
          <span v-if="modKeyLabel === 'Ctrl'" class="palette-keycap-plus">+</span>
          <span class="palette-keycap">K</span>
          <span class="palette-empty-shortcut__text">{{ $t('common.searchShortcutHint') }}</span>
        </p>
      </template>
      <NScrollbar v-else>
        <div class="pb-12px">
          <SearchResult
            v-if="resultOptions.length > 0"
            v-model:path="activePath"
            :options="resultOptions"
            @enter="handleEnter"
          />
          <div v-if="showDocSection" class="palette-doc-section">
            <p class="palette-doc-section__title">{{ $t('common.searchDocSection') }}</p>
            <div
              v-for="item in docResults"
              :key="`${item.fileMd5}-${item.chunkId}`"
              class="mt-4px h-44px flex-y-center cursor-pointer gap-10px rounded-10px px-14px transition-colors duration-150 ease-[var(--app-ease-standard)] hover:bg-fill-subtle"
              @click="handleDocClick"
            >
              <icon-lucide:file-text class="shrink-0 text-icon text-tertiary" />
              <span class="flex-1 truncate">{{ item.fileName }}</span>
              <icon-lucide:corner-down-left class="mr-3px p-2px text-icon" />
            </div>
          </div>
        </div>
      </NScrollbar>
    </div>
    <template #footer>
      <SearchFooter v-if="!isMobile" />
    </template>
  </NModal>
</template>

<style lang="scss" scoped>
/* pm-014 文档分组标题：同 .table-state__desc 的次级墨 token，分组沿用菜单组列表行高/圆角/hover */
.palette-doc-section__title {
  margin: 12px 0 4px;
  padding: 0 14px;
  font-size: 12px;
  color: var(--app-muted);
}

/* 空态快捷键提示：键帽复用 search-footer operate-key 家族（token 化材质），仅文字静置无动画 */
.palette-empty-shortcut {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  margin: 12px 0 0;
}

.palette-keycap {
  padding: 2px 6px;
  font-size: 12px;
  font-weight: 500;
  background: var(--app-fill-subtle);
  border-radius: 4px;
  box-shadow: inset 0 -2px var(--app-fill-hover);
}

.palette-keycap-plus {
  color: var(--app-muted);
  font-size: 12px;
}

.palette-empty-shortcut__text {
  margin-left: 6px;
  font-size: 12px;
  color: var(--app-muted);
}
</style>
