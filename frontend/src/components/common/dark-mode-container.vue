<script setup lang="ts">
defineOptions({ name: 'DarkModeContainer' });

interface Props {
  inverted?: boolean;
}

defineProps<Props>();
</script>

<template>
  <!--
 text on the inverted surface routes through --app-inverted-text (R7 colorize re-review):
       light theme renders a cold-ink inverted surface so its text is the quiet-glass light value,
       dark theme inverts to a light surface with cold-ink text — both AA, zero hardcoded hex. 
-->
  <div
    class="bg-container text-base-text transition-300"
    :class="{ 'bg-inverted text-[var(--app-inverted-text)]': inverted }"
  >
    <slot></slot>
  </div>
</template>

<style scoped></style>
