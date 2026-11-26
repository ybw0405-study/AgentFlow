<script setup lang="ts">
import DesignIcon from './DesignIcon.vue'

/** 控制台页眉：编号图签、仪器图标与中英对照标题压成一条 96px 的带。 */
defineProps<{
  no: string
  code: string
  title: string
  zh: string
  icon: string
  aside?: string
}>()
</script>

<template>
  <header class="af-masthead af-reveal is-in">
    <div class="af-masthead__body">
      <span class="af-fig-tag">FIG. {{ no }} / {{ code }}</span>
      <h1 class="af-masthead__title">
        {{ title }}<span class="af-masthead__zh">{{ zh }}</span>
      </h1>
      <p
        v-if="aside"
        class="af-masthead__aside"
      >{{ aside }}</p>
    </div>
    <div
      class="af-masthead__plate"
      aria-hidden="true"
    >
      <DesignIcon
        :name="icon"
        :size="34"
      />
      <span>CONSOLE / {{ no }}</span>
    </div>
  </header>
</template>
