<script lang="ts" setup>
import { $t } from '@/locales';

defineOptions({ name: 'SearchResult' });

interface Props {
  options: App.Global.Menu[];
}

defineProps<Props>();

interface Emits {
  (e: 'enter'): void;
}

const emit = defineEmits<Emits>();

const active = defineModel<string>('path', { required: true });

async function handleMouseEnter(item: App.Global.Menu) {
  active.value = item.routePath;
}

function handleTo() {
  emit('enter');
}
</script>

<template>
  <NScrollbar>
    <div class="pb-12px">
      <template v-for="item in options" :key="item.routePath">
        <div
          class="mt-4px h-44px flex-y-center cursor-pointer justify-between rounded-10px px-14px transition-colors duration-150 ease-[var(--app-ease-standard)]"
          :class="item.routePath === active ? 'bg-fill-hover text-primary' : 'hover:bg-fill-subtle'"
          @click="handleTo"
          @mouseenter="handleMouseEnter(item)"
        >
          <component :is="item.icon" />
          <span class="ml-5px flex-1">
            {{ (item.i18nKey && $t(item.i18nKey)) || item.label }}
          </span>
          <icon-ant-design-enter-outlined class="icon mr-3px p-2px text-20px" />
        </div>
      </template>
    </div>
  </NScrollbar>
</template>

<style lang="scss" scoped></style>
