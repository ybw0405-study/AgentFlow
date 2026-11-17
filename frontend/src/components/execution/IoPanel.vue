<script setup lang="ts">
import type { NodeIo } from '@/types/api'

defineOptions({ name: 'IoPanel' })

defineProps<{
  io: NodeIo | null
  loading: boolean
  nodeKey: string | null
}>()

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2)
}
</script>

<template>
  <div style="padding: 12px; overflow: auto; height: 100%">
    <div
      class="af-label"
      style="margin-top: 0"
    >
      节点 I/O <span
        v-if="nodeKey"
        class="mono af-accent"
      >{{ nodeKey }}</span>
    </div>
    <div
      v-if="loading"
      class="af-faint"
    >
      加载中…
    </div>
    <div
      v-else-if="!io"
      class="af-faint"
    >
      在左侧选择节点查看输入/输出原文
    </div>
    <template v-else>
      <div class="af-label">
        输入（IN · {{ io.in?.contentHash?.slice(0, 12) ?? '—' }}）
      </div>
      <pre class="mono af-io">{{ io.in ? pretty(io.in.payload) : '—' }}</pre>
      <div class="af-label">
        输出（OUT · {{ io.out?.contentHash?.slice(0, 12) ?? '—' }}）
      </div>
      <pre class="mono af-io">{{ io.out ? pretty(io.out.payload) : '—' }}</pre>
    </template>
  </div>
</template>

<style scoped>
.af-io {
  background: var(--af-surface);
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius-sm);
  padding: 8px 10px;
  font-size: 11.5px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--af-text-dim);
  max-height: 300px;
  overflow: auto;
}
</style>
