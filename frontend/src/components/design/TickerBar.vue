<script setup lang="ts">
withDefaults(defineProps<{ items: readonly string[]; tone?: 'blue' | 'ink' }>(), { tone: 'blue' })
</script>

<template>
  <div
    class="af-ticker"
    :class="`af-ticker--${tone}`"
    aria-hidden="true"
  >
    <div class="af-ticker__track">
      <span v-for="copy in 2" :key="copy">
        <template v-for="(item, index) in items" :key="`${copy}-${index}`">
          <i>◆</i>{{ item }}
        </template>
      </span>
    </div>
  </div>
</template>
