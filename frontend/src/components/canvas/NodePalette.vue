<script setup lang="ts">
import { NODE_TYPE_METAS } from '@/dsl/types'
import type { NodeType } from '@/dsl/types'

defineOptions({ name: 'NodePalette' })

const emit = defineEmits<{ add: [type: NodeType] }>()
</script>

<template>
  <aside class="af-palette">
    <div
      class="af-label"
      style="margin-top: 0"
    >
      节点
    </div>
    <button
      v-for="meta in NODE_TYPE_METAS"
      :key="meta.type"
      class="af-palette__item"
      :title="meta.description"
      @click="emit('add', meta.type)"
    >
      <span class="mono af-palette__type">{{ meta.type }}</span>
      <span class="af-dim af-palette__label">{{ meta.label }}</span>
    </button>
    <div
      class="af-faint"
      style="font-size: 11px; margin-top: 12px; line-height: 1.6"
    >
      P0 支持 start / llm / tool / end。<br>其余节点类型在 P1 开放。
    </div>
  </aside>
</template>

<style scoped>
.af-palette {
  width: 168px;
  flex: none;
  padding: 12px;
  border-right: 1px solid var(--af-border);
  background: var(--af-panel);
  overflow: auto;
}

.af-palette__item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
  text-align: left;
  padding: 8px 10px;
  margin-bottom: 6px;
  background: var(--af-surface);
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius);
  cursor: pointer;
  transition: border-color var(--af-motion);
}

.af-palette__item:hover {
  border-color: var(--af-accent-border);
}

.af-palette__type {
  color: var(--af-accent);
  font-size: 11px;
}

.af-palette__label {
  font-size: 12px;
}
</style>
