<script setup lang="ts">
import { NODE_TYPE_METAS } from '@/dsl/types'
import type { NodeType } from '@/dsl/types'

defineOptions({ name: 'NodePalette' })

const props = defineProps<{ startTaken: boolean; inSubgraph?: boolean }>()

const emit = defineEmits<{ add: [type: NodeType] }>()

function disabled(type: NodeType): boolean {
  // 子图由上层节点进入，没有 start 的位置；这里直接禁用，避免建出必被引擎拒绝的节点
  if (type === 'start') return props.inSubgraph === true || props.startTaken
  return false
}

function titleFor(meta: { type: NodeType; description: string }): string {
  if (!disabled(meta.type)) return meta.description
  return props.inSubgraph === true
    ? '子图内不能有 start 节点：入口由上层节点决定'
    : '已存在 start 节点：一个工作流只能有一个'
}
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
      :disabled="disabled(meta.type)"
      :title="titleFor(meta)"
      @click="emit('add', meta.type)"
    >
      <span class="mono af-palette__type">{{ meta.type }}</span>
      <span class="af-dim af-palette__label">{{ meta.label }}</span>
    </button>
    <div
      class="af-faint"
      style="font-size: 11px; margin-top: 12px; line-height: 1.6"
    >
      <template v-if="inSubgraph">
        当前在子图内：入口由上层节点决定，没有 start；必须保留至少一个 end。
      </template>
      <template v-else>
        选中节点在右侧配置；condition / human_gate 的出边分支在画布底部设置。
        <br>retrieval 引擎尚未实现执行器，暂不提供。
      </template>
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

.af-palette__item:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.af-palette__item:disabled:hover {
  border-color: var(--af-border);
}

.af-palette__type {
  color: var(--af-accent);
  font-size: 11px;
}

.af-palette__label {
  font-size: 12px;
}
</style>
