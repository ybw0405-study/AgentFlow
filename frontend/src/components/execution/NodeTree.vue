<script setup lang="ts">
import type { NodeExecutionDto } from '@/types/api'

defineOptions({ name: 'NodeTree' })

defineProps<{
  nodes: NodeExecutionDto[]
  selectedKey: string | null
}>()

const emit = defineEmits<{ select: [nodeKey: string] }>()

function statusClass(s: string): string {
  return {
    RUNNING: 'af-status--running',
    FAILED: 'af-status--failed',
    TIMED_OUT: 'af-status--timed_out',
    SKIPPED: 'af-status--skipped',
  }[s] ?? ''
}
</script>

<template>
  <div class="af-tree">
    <div
      class="af-label"
      style="margin: 0 0 8px"
    >
      节点树
    </div>
    <div
      v-for="n in nodes"
      :key="`${n.nodeKey}#${n.attempt}`"
      class="af-tree__row"
      :class="{ 'af-tree__row--selected': selectedKey === n.nodeKey }"
      @click="emit('select', n.nodeKey)"
    >
      <span
        class="af-status"
        :class="statusClass(n.status)"
      >{{ n.status }}</span>
      <span
        class="mono"
        style="margin-left: 8px"
      >{{ n.nodeKey }}</span>
      <span
        v-if="n.attempt > 0"
        class="af-faint mono"
        style="margin-left: 4px"
      >
        #{{ n.attempt }}
      </span>
      <div class="af-spacer" />
      <span
        v-if="n.tokenUsed"
        class="af-faint mono"
        style="font-size: 10px"
      >
        {{ n.tokenUsed }} tok
      </span>
      <span
        v-if="n.latencyMs != null"
        class="af-faint mono"
        style="font-size: 10px; margin-left: 6px"
      >
        {{ n.latencyMs }}ms
      </span>
    </div>
    <div
      v-if="!nodes.length"
      class="af-faint"
      style="font-size: 12px"
    >
      等待事件…
    </div>
  </div>
</template>

<style scoped>
.af-tree {
  padding: 12px;
  overflow: auto;
}

.af-tree__row {
  display: flex;
  align-items: center;
  padding: 5px 8px;
  border-radius: var(--af-radius-sm);
  cursor: pointer;
  font-size: 12px;
  transition: background var(--af-motion);
}

.af-tree__row:hover {
  background: var(--af-surface);
}

.af-tree__row--selected {
  background: var(--af-accent-dim);
  outline: 1px solid var(--af-accent-border);
}
</style>
