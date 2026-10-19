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
          class="mt-8px h-56px flex-y-center cursor-pointer justify-between rounded-6px bg-transparent px-14px hover:bg-[rgb(var(--base-text-color)/0.055)]"
          :class="{ '!bg-[rgb(var(--primary-color)/0.08)] text-primary': item.routePath === active }"
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
