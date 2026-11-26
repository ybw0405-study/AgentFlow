<script setup lang="ts">
defineProps<{ no: string; title: string; zh?: string; aside?: string }>()
</script>

<template>
  <header class="af-section-head af-reveal">
    <div>
      <span class="af-section-head__no">SEC. {{ no }} / {{ title }}</span>
      <h2 class="af-section-head__title">
        {{ title }}<span v-if="zh" class="af-section-head__zh">{{ zh }}</span>
      </h2>
    </div>
    <p v-if="aside" class="af-section-head__aside">{{ aside }}</p>
  </header>
</template>
