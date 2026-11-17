<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'

defineOptions({ name: 'WorkflowNode' })

defineProps<{
  data: { nodeType: string; label: string }
  selected?: boolean
}>()
</script>

<template>
  <div
    class="af-node"
    :class="{ 'af-node--selected': selected }"
  >
    <Handle
      v-if="data.nodeType !== 'end'"
      type="source"
      :position="Position.Right"
    />
    <Handle
      v-if="data.nodeType !== 'start'"
      type="target"
      :position="Position.Left"
    />
    <div class="af-node__type mono">
      {{ data.nodeType }}
    </div>
    <div class="af-node__label">
      {{ data.label }}
    </div>
  </div>
</template>

<style scoped>
.af-node {
  min-width: 130px;
  background: var(--af-surface);
  border: 1px solid var(--af-border-strong);
  border-radius: var(--af-radius);
  padding: 8px 10px;
  transition: border-color var(--af-motion);
}

.af-node--selected {
  border-color: var(--af-accent);
  box-shadow: 0 0 0 1px var(--af-accent-border);
}

.af-node__type {
  font-size: 10px;
  color: var(--af-text-faint);
  text-transform: uppercase;
  letter-spacing: 0.1em;
}

.af-node__label {
  font-size: 12.5px;
  color: var(--af-text);
  margin-top: 2px;
}

:deep(.vue-flow__handle) {
  width: 8px;
  height: 8px;
  background: var(--af-raised);
  border: 1.5px solid var(--af-neutral);
}

:deep(.vue-flow__handle:hover) {
  border-color: var(--af-accent);
}
</style>
