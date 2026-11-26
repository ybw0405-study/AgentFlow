<script setup lang="ts">
import { computed } from 'vue'

/**
 * 线性图标族：统一走 24×24 网格与 currentColor，
 * 不引入图标库，也不在组件内写色值。
 */
type Shape = { tag: 'path' | 'circle' | 'rect'; attrs: Record<string, string> }

const props = withDefaults(defineProps<{ name: string; size?: number | string; stroke?: number }>(), {
  size: 18,
  stroke: 1.7,
})

const ICONS: Record<string, Shape[]> = {
  canvas: [
    { tag: 'rect', attrs: { x: '3', y: '3', width: '7', height: '7', rx: '1.5' } },
    { tag: 'rect', attrs: { x: '14', y: '14', width: '7', height: '7', rx: '1.5' } },
    { tag: 'path', attrs: { d: 'M10 6.5h6.5v7.5M6.5 10v6.5H14' } },
  ],
  engine: [
    { tag: 'path', attrs: { d: 'M12 2v4M12 18v4M2 12h4M18 12h4M5 5l2.8 2.8M16.2 16.2L19 19M19 5l-2.8 2.8M7.8 16.2L5 19' } },
    { tag: 'circle', attrs: { cx: '12', cy: '12', r: '3.2' } },
  ],
  shield: [
    { tag: 'path', attrs: { d: 'M12 2l8 3.5v5.5c0 5-3.4 8.6-8 10-4.6-1.4-8-5-8-10V5.5L12 2z' } },
    { tag: 'path', attrs: { d: 'M9 11.5l2.2 2.2L15.5 9' } },
  ],
  budget: [
    { tag: 'circle', attrs: { cx: '12', cy: '12', r: '9' } },
    { tag: 'path', attrs: { d: 'M12 7v5l3.5 2' } },
  ],
  mcp: [
    { tag: 'path', attrs: { d: 'M12 2l8 4.5v9L12 20l-8-4.5v-9L12 2z' } },
    { tag: 'path', attrs: { d: 'M12 11l8-4.5M12 11v9M12 11L4 6.5' } },
  ],
  llm: [
    { tag: 'path', attrs: { d: 'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3z' } },
    { tag: 'path', attrs: { d: 'M12 17v4M9 21h6' } },
  ],
  tool: [
    { tag: 'path', attrs: { d: 'M14.7 6.3a4.5 4.5 0 0 0-6 6L3 18l3 3 5.7-5.7a4.5 4.5 0 0 0 6-6L14 13l-3-3 3.7-3.7z' } },
  ],
  retrieval: [
    { tag: 'circle', attrs: { cx: '11', cy: '11', r: '7' } },
    { tag: 'path', attrs: { d: 'M21 21l-4.3-4.3M8 11h6M11 8v6' } },
  ],
  branch: [
    { tag: 'path', attrs: { d: 'M6 3v6a3 3 0 0 0 3 3h6a3 3 0 0 1 3 3v6M6 3l-2.5 3M6 3l2.5 3M18 21l-2.5-3M18 21l2.5-3' } },
  ],
  loop: [
    { tag: 'path', attrs: { d: 'M17 2l4 4-4 4' } },
    { tag: 'path', attrs: { d: 'M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4 4-4' } },
    { tag: 'path', attrs: { d: 'M21 13v1a4 4 0 0 1-4 4H3' } },
  ],
  human: [
    { tag: 'circle', attrs: { cx: '12', cy: '8', r: '4' } },
    { tag: 'path', attrs: { d: 'M4 21c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5' } },
  ],
  arrow: [
    { tag: 'path', attrs: { d: 'M5 12h14M13 6l6 6-6 6' } },
  ],
  book: [
    { tag: 'path', attrs: { d: 'M4 4.5A2.5 2.5 0 0 1 6.5 2H20v18H6.5A2.5 2.5 0 0 0 4 22V4.5z' } },
    { tag: 'path', attrs: { d: 'M8 7h8M8 11h6' } },
  ],
  clock: [
    { tag: 'circle', attrs: { cx: '12', cy: '12', r: '9' } },
    { tag: 'path', attrs: { d: 'M12 7v5l3 2' } },
  ],
  chart: [
    { tag: 'path', attrs: { d: 'M4 20V10M10 20V4M16 20v-7M22 20H2' } },
  ],
  flow: [
    { tag: 'rect', attrs: { x: '2.5', y: '9', width: '6', height: '6', rx: '1' } },
    { tag: 'rect', attrs: { x: '15.5', y: '3', width: '6', height: '6', rx: '1' } },
    { tag: 'rect', attrs: { x: '15.5', y: '15', width: '6', height: '6', rx: '1' } },
    { tag: 'path', attrs: { d: 'M8.5 12h3.5V6h3.5M12 12v6h3.5' } },
  ],
}

const shapes = computed(() => ICONS[props.name] ?? ICONS.canvas ?? [])
</script>

<template>
  <svg
    class="af-design-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    :stroke-width="stroke"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <component
      :is="shape.tag"
      v-for="(shape, index) in shapes"
      :key="index"
      v-bind="shape.attrs"
    />
  </svg>
</template>
