<script setup lang="ts">
import { computed } from 'vue'
import type { DslNode } from '@/dsl/types'

defineOptions({ name: 'ConfigDrawer' })

const props = defineProps<{
  node: DslNode | null
  issues: { nodeKey?: string; field?: string; message: string }[]
}>()

const emit = defineEmits<{
  update: [node: DslNode]
  close: []
  remove: [key: string]
}>()

const nodeIssues = computed(() =>
  props.node ? props.issues.filter((i) => i.nodeKey === props.node?.key) : [],
)

function cfg<T>(key: string, fallback: T): T {
  const c = props.node?.config as Record<string, unknown> | undefined
  return (c?.[key] as T | undefined) ?? fallback
}

function setCfg(key: string, value: unknown): void {
  if (!props.node) return
  emit('update', { ...props.node, config: { ...props.node.config, [key]: value } })
}

function setName(value: string): void {
  if (!props.node) return
  emit('update', { ...props.node, name: value || undefined })
}

function setTimeoutMs(value: string): void {
  const n = Number(value)
  setCfg('timeoutMs', Number.isFinite(n) && n > 0 ? n : undefined)
}

// paramsMapping / resultMapping 行编辑
function mappingEntries(key: 'paramsMapping' | 'resultMapping'): [string, string][] {
  return Object.entries(cfg<Record<string, string>>(key, {}))
}

function setMappingEntry(
  key: 'paramsMapping' | 'resultMapping',
  index: number,
  field: 'k' | 'v',
  value: string,
): void {
  const entries = mappingEntries(key)
  if (!entries.length) return
  if (field === 'k') entries[index][0] = value
  else entries[index][1] = value
  setCfg(key, Object.fromEntries(entries.filter(([k]) => k)))
}

function addMappingEntry(key: 'paramsMapping' | 'resultMapping'): void {
  const entries = mappingEntries(key)
  entries.push([`key${entries.length + 1}`, ''])
  setCfg(key, Object.fromEntries(entries))
}

function removeMappingEntry(key: 'paramsMapping' | 'resultMapping', index: number): void {
  const entries = mappingEntries(key)
  entries.splice(index, 1)
  setCfg(key, Object.fromEntries(entries))
}
</script>

<template>
  <aside
    v-if="node"
    class="af-drawer"
  >
    <div class="af-drawer__head">
      <span class="mono af-accent">{{ node.type }}</span>
      <span class="af-dim">{{ node.key }}</span>
      <div class="af-spacer" />
      <button
        class="af-btn af-btn--sm"
        title="Esc"
        @click="emit('close')"
      >
        ×
      </button>
    </div>

    <div
      v-if="nodeIssues.length"
      class="af-error-box"
      style="margin-bottom: 10px"
    >
      <div
        v-for="(issue, i) in nodeIssues"
        :key="i"
      >
        {{ issue.field ? `${issue.field}: ` : '' }}{{ issue.message }}
      </div>
    </div>

    <label class="af-label">显示名</label>
    <input
      class="af-input"
      :value="node.name ?? ''"
      @input="setName(($event.target as HTMLInputElement).value)"
    >

    <template v-if="node.type === 'llm'">
      <label class="af-label">provider</label>
      <select
        class="af-select"
        :value="cfg('provider', 'mock')"
        @change="setCfg('provider', ($event.target as HTMLSelectElement).value)"
      >
        <option value="mock">
          mock（离线）
        </option>
        <option value="openai-compatible">
          openai-compatible（真实模型 · 需配置 AF_LLM_*）
        </option>
      </select>
      <label class="af-label">model</label>
      <input
        class="af-input mono"
        :value="cfg('model', '')"
        @input="setCfg('model', ($event.target as HTMLInputElement).value)"
      >
      <label class="af-label">promptTemplate（Jinja2 沙箱）</label>
      <textarea
        class="af-textarea"
        rows="6"
        :value="cfg('promptTemplate', '')"
        @input="setCfg('promptTemplate', ($event.target as HTMLTextAreaElement).value)"
      />
      <label class="af-label">maxTokens</label>
      <input
        class="af-input mono"
        type="number"
        :value="cfg('maxTokens', 1024)"
        @input="setCfg('maxTokens', Number(($event.target as HTMLInputElement).value))"
      >
      <label class="af-label">temperature</label>
      <input
        class="af-input mono"
        type="number"
        step="0.1"
        min="0"
        max="2"
        :value="cfg('temperature', 0.7)"
        @input="setCfg('temperature', Number(($event.target as HTMLInputElement).value))"
      >
    </template>

    <template v-else-if="node.type === 'tool'">
      <label class="af-label">connectorId</label>
      <input
        class="af-input mono"
        :value="cfg('connectorId', 'mock.search')"
        @input="setCfg('connectorId', ($event.target as HTMLInputElement).value)"
      >
      <label class="af-label">operation</label>
      <input
        class="af-input mono"
        :value="cfg('operation', '')"
        @input="setCfg('operation', ($event.target as HTMLInputElement).value)"
      >
      <label class="af-label">paramsMapping</label>
      <div
        v-for="([k, v], i) in mappingEntries('paramsMapping')"
        :key="i"
        style="display: flex; gap: 6px; margin-bottom: 4px"
      >
        <input
          class="af-input mono"
          :value="k"
          placeholder="参数名"
          @input="setMappingEntry('paramsMapping', i, 'k', ($event.target as HTMLInputElement).value)"
        >
        <input
          class="af-input mono"
          :value="v"
          placeholder="{{ vars.x }}"
          @input="setMappingEntry('paramsMapping', i, 'v', ($event.target as HTMLInputElement).value)"
        >
        <button
          class="af-btn af-btn--sm"
          @click="removeMappingEntry('paramsMapping', i)"
        >
          ×
        </button>
      </div>
      <button
        class="af-btn af-btn--sm"
        @click="addMappingEntry('paramsMapping')"
      >
        + 参数
      </button>
    </template>

    <template v-else-if="node.type === 'end'">
      <label class="af-label">resultMapping</label>
      <div
        v-for="([k, v], i) in mappingEntries('resultMapping')"
        :key="i"
        style="display: flex; gap: 6px; margin-bottom: 4px"
      >
        <input
          class="af-input mono"
          :value="k"
          placeholder="输出名"
          @input="setMappingEntry('resultMapping', i, 'k', ($event.target as HTMLInputElement).value)"
        >
        <input
          class="af-input mono"
          :value="v"
          placeholder="{{ nodes.x.output.text }}"
          @input="setMappingEntry('resultMapping', i, 'v', ($event.target as HTMLInputElement).value)"
        >
        <button
          class="af-btn af-btn--sm"
          @click="removeMappingEntry('resultMapping', i)"
        >
          ×
        </button>
      </div>
      <button
        class="af-btn af-btn--sm"
        @click="addMappingEntry('resultMapping')"
      >
        + 映射
      </button>
    </template>

    <template v-else>
      <p
        class="af-faint"
        style="font-size: 12px"
      >
        start 节点无配置；入参在「变量」面板维护。
      </p>
    </template>

    <label class="af-label">timeoutMs</label>
    <input
      class="af-input mono"
      type="number"
      :value="node.timeoutMs ?? ''"
      placeholder="默认 30000"
      @input="setTimeoutMs(($event.target as HTMLInputElement).value)"
    >

    <div style="margin-top: 20px">
      <button
        v-if="node.type !== 'start'"
        class="af-btn af-btn--danger af-btn--sm"
        @click="emit('remove', node.key)"
      >
        删除节点（Del）
      </button>
    </div>
  </aside>
</template>

<style scoped>
.af-drawer {
  width: 320px;
  flex: none;
  background: var(--af-panel);
  border-left: 1px solid var(--af-border);
  padding: 14px;
  overflow: auto;
}

.af-drawer__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
