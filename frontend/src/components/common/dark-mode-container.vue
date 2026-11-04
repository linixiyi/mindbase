<script setup lang="ts">
defineOptions({ name: 'DarkModeContainer' });

interface Props {
  inverted?: boolean;
}

defineProps<Props>();
</script>

<template>
  <div
    class="bg-container text-base-text transition-300"
    :class="{ 'bg-inverted text-[var(--app-inverted-text)]': inverted }"
  >
    <slot></slot>
  </div>
</template>

<!--
  The template MUST keep a single root element: an HTML comment at template root survives dev
  compilation, makes this component fragment-rooted, and then `$el` (global-header hairline reads
  it) resolves to the fragment's comment anchor instead of the div — `.classList` turns undefined.
-->
<style scoped>
/* text on the inverted surface routes through --app-inverted-text (R7 colorize re-review):
   light theme renders a cold-ink inverted surface so its text is the quiet-glass light value,
   dark theme inverts to a light surface with cold-ink text — both AA, zero hardcoded hex. */
</style>
