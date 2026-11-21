<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import {
  categoryColor,
  inlineFields,
  nodeCategory,
  nodeSummary,
  statusColor,
} from '@/dsl/node-card'
import type { NodeType } from '@/dsl/types'

defineOptions({ name: 'WorkflowNode' })

interface CardData {
  nodeType: NodeType
  label: string
  config: Record<string, unknown>
  /** 执行态：节点状态、输出摘要与失败原因；编排态为空。 */
  status?: string | null
  outputDigest?: string | null
  errorCode?: string | null
  errorMessage?: string | null
  /** 执行态只读：字段不退化为输入框，按钮改为查看 I/O。 */
  readOnly?: boolean
}

const props = defineProps<{
  id: string
  data: CardData
  selected?: boolean
}>()

const emit = defineEmits<{
  'update-config': [patch: Record<string, unknown>]
  expand: []
  'pick-model': []
  preview: []
}>()

const accent = computed(() => categoryColor(nodeCategory(props.data.nodeType)))
const status = computed(() => statusColor(props.data.status))
const isLive = computed(() =>
  ['RUNNING', 'SUSPENDED', 'PENDING'].includes(props.data.status ?? ''),
)
/** 循环 / 并行 / 审批无法裁剪出可试跑的小图，卡片上不提供入口（后端也会拒绝）。 */
const previewable = computed(
  () =>
    !props.data.readOnly &&
    !['start', 'end', 'loop', 'parallel', 'human_gate'].includes(props.data.nodeType),
)
const fields = computed(() => inlineFields(props.data.nodeType, props.data.config ?? {}))
const summary = computed(() => nodeSummary(props.data.nodeType, props.data.config ?? {}))

function write(fieldKey: string, kind: string, raw: string): void {
  if (kind === 'number') {
    const n = Number(raw)
    // 清空/非法输入写 undefined（落 DSL 时被忽略），避免 Number('')===0 或 NaN→null
    emit('update-config', { [fieldKey]: Number.isFinite(n) && n > 0 ? n : undefined })
  } else {
    emit('update-config', { [fieldKey]: raw })
  }
}
</script>

<template>
  <div
    class="af-node"
    :class="{ 'af-node--selected': selected, 'af-node--live': isLive }"
    :style="{ '--af-node-accent': accent }"
  >
    <Handle
      v-if="data.nodeType !== 'end'"
      type="source"
      :position="Position.Right"
      class="af-node__port"
      :style="{ background: accent, borderColor: accent }"
    />
    <Handle
      v-if="data.nodeType !== 'start'"
      type="target"
      :position="Position.Left"
      class="af-node__port"
      :style="{ background: accent, borderColor: accent }"
    />

    <div class="af-node__head">
      <span class="af-node__kind mono">{{ data.nodeType }}</span>
      <span class="af-node__title">{{ data.label }}</span>
      <span
        v-if="status"
        class="af-node__dot"
        :style="{ background: status }"
        :title="data.status ?? ''"
      />
    </div>

    <div class="af-node__body">
      <div
        v-for="f in fields"
        :key="f.key"
        class="af-node__field"
      >
        <span class="af-node__label">{{ f.label }}</span>
        <select
          v-if="f.kind === 'select' && !f.readOnly && !data.readOnly"
          class="af-node__input nodrag mono"
          :value="f.value"
          @mousedown.stop
          @pointerdown.stop
          @click.stop
          @keydown.stop
          @change="write(f.key, f.kind, ($event.target as HTMLSelectElement).value)"
        >
          <option
            v-for="o in f.options ?? []"
            :key="o"
            :value="o"
          >
            {{ o }}
          </option>
        </select>
        <button
          v-else-if="f.kind === 'picker' && !f.readOnly && !data.readOnly"
          class="af-node__picker nodrag"
          :title="`${f.value} · 点击更换模型`"
          @mousedown.stop
          @pointerdown.stop
          @click.stop="emit('pick-model')"
          @keydown.stop
        >
          <span class="af-node__picker-value">{{ f.value }}</span>
          <span class="af-node__picker-caret">更换</span>
        </button>
        <input
          v-else-if="!f.readOnly && !data.readOnly"
          class="af-node__input nodrag mono"
          :type="f.kind === 'number' ? 'number' : 'text'"
          :value="f.value"
          spellcheck="false"
          @mousedown.stop
          @pointerdown.stop
          @click.stop
          @keydown.stop
          @input="write(f.key, f.kind, ($event.target as HTMLInputElement).value)"
        >
        <span
          v-else
          class="af-node__value mono"
          :title="f.value"
        >{{ f.value || '—' }}</span>
      </div>

      <div
        v-if="summary"
        class="af-node__summary"
        :title="summary"
      >
        {{ summary }}
      </div>
      <div
        v-if="data.errorMessage"
        class="af-node__error mono"
        :title="data.errorMessage"
      >
        {{ data.errorCode }} · {{ data.errorMessage }}
      </div>
      <div
        v-else-if="data.outputDigest"
        class="af-node__output mono"
        :title="data.outputDigest"
      >
        输出 {{ data.outputDigest }}
      </div>
    </div>

    <div class="af-node__actions">
      <button
        v-if="previewable"
        class="af-node__expand nodrag"
        title="只跑这个节点和它的上游，看真实输出"
        @mousedown.stop
        @pointerdown.stop
        @click.stop="emit('preview')"
      >
        试跑
      </button>
      <button
        class="af-node__expand nodrag"
        @mousedown.stop
        @pointerdown.stop
        @click.stop="emit('expand')"
      >
        {{ data.readOnly ? '查看输入 / 输出' : '展开完整配置' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.af-node {
  width: 196px;
  background: var(--af-surface);
  border: 1px solid var(--af-border-strong);
  border-radius: var(--af-radius);
  overflow: hidden;
  transition: border-color var(--af-motion);
}

.af-node--selected {
  border-color: var(--af-accent);
  box-shadow: 0 0 0 1px var(--af-accent-border);
}

.af-node--live {
  border-color: var(--af-accent-border);
}

@media (prefers-reduced-motion: no-preference) {
  .af-node--live {
    animation: af-node-live 1.8s ease-in-out infinite;
  }

  @keyframes af-node-live {
    0%, 100% { border-color: var(--af-accent-border); }
    50% { border-color: var(--af-accent); }
  }
}

.af-node__head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  background: var(--af-raised);
  border-bottom: 1px solid var(--af-border);
  border-left: 3px solid var(--af-node-accent);
}

.af-node__kind {
  font-size: 10px;
  color: var(--af-text-faint);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.af-node__title {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--af-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.af-node__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
}

.af-node__body {
  padding: 7px 8px;
}

.af-node__field {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 5px;
}

.af-node__label {
  width: 60px;
  flex: none;
  font-size: 11px;
  color: var(--af-text-faint);
}

.af-node__input {
  flex: 1;
  min-width: 0;
  background: var(--af-panel);
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius-sm);
  padding: 3px 5px;
  font-size: 11px;
  color: var(--af-text);
  outline: none;
}

.af-node__input:focus {
  border-color: var(--af-accent-border);
}

.af-node__picker {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  background: var(--af-panel);
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius-sm);
  padding: 3px 5px;
  color: var(--af-text);
  text-align: left;
  cursor: pointer;
}

.af-node__picker:hover {
  border-color: var(--af-accent-border);
}

.af-node__picker-value {
  flex: 1;
  min-width: 0;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.af-node__picker-caret {
  flex: none;
  font-size: 10px;
  color: var(--af-text-faint);
}

.af-node__picker:hover .af-node__picker-caret {
  color: var(--af-accent);
}

.af-node__value {
  flex: 1;
  min-width: 0;
  font-size: 11px;
  color: var(--af-text-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.af-node__summary,
.af-node__output,
.af-node__error {
  margin-top: 4px;
  font-size: 11px;
  color: var(--af-text-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.af-node__error {
  color: var(--af-danger);
}

.af-node__actions {
  display: flex;
  border-top: 1px solid var(--af-border);
}

.af-node__expand {
  flex: 1;
  padding: 5px 8px;
  background: var(--af-panel);
  border: none;
  color: var(--af-text-dim);
  font-size: 11px;
  text-align: left;
  cursor: pointer;
}

.af-node__expand + .af-node__expand {
  border-left: 1px solid var(--af-border);
}

.af-node__expand:hover {
  color: var(--af-accent);
}

:deep(.vue-flow__handle.af-node__port) {
  width: 10px;
  height: 10px;
  border-width: 2px;
  border-style: solid;
}

:deep(.vue-flow__handle.af-node__port:hover) {
  box-shadow: 0 0 0 3px var(--af-accent-dim);
}
</style>
