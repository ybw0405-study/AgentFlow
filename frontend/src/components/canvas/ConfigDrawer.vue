<script setup lang="ts">
import { computed } from 'vue'
import type { DslNode } from '@/dsl/types'

defineOptions({ name: 'ConfigDrawer' })

const props = defineProps<{
  node: DslNode | null
  issues: { nodeKey?: string; field?: string; message: string }[]
  /** 模型清单的加载态；loaded=false 表示画布仍在拉取。 */
  modelCatalog?: {
    loaded: boolean
    configured: boolean
    error: string | null
    models: string[]
  } | null
  /** 已启用且属于本空间的模型连接；选中后模型从该连接的清单里挑。 */
  llmConnections?: {
    id: string
    name: string
    models: string[]
    defaultModel: string
  }[]
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

/** provider 侧会把 mock-* 占位名回落到服务端默认模型，界面上要说清"实际会下发什么"。 */
const modelWillFallback = computed(() => {
  if (!props.node) return false
  const c = props.node.config as Record<string, unknown>
  if (c.provider !== 'openai-compatible') return false
  const model = String(c.model ?? '')
  return !model || model.toLowerCase().startsWith('mock')
})

const connectionId = computed(() => cfg<string>('connectionId', ''))

/** 当前选中的模型连接；为空表示走进程级 AF_LLM_*。 */
const activeConnection = computed(
  () => props.llmConnections?.find((c) => c.id === connectionId.value) ?? null,
)

/**
 * 切换模型来源。切到某个连接时把 model 对齐到该连接的模型：留着上一个来源的模型名
 * 会被上游判 400，而节点配置又看不出这层关系。
 */
function setSource(id: string): void {
  if (!props.node) return
  if (!id) {
    emit('update', {
      ...props.node,
      config: { ...props.node.config, connectionId: undefined },
    })
    return
  }
  const target = props.llmConnections?.find((c) => c.id === id)
  const current = cfg<string>('model', '')
  const model =
    target && target.models.length && !target.models.includes(current)
      ? target.defaultModel || target.models[0]
      : current
  emit('update', {
    ...props.node,
    config: { ...props.node.config, connectionId: id, model },
  })
}

const modelOptions = computed(() => props.modelCatalog?.models ?? [])

const modelStatus = computed<string>(() => {
  const catalog = props.modelCatalog
  if (!catalog || !catalog.loaded) return '正在读取模型清单…'
  if (!catalog.configured) return '服务端未配置 AF_LLM_*，暂时列不出模型；请先配置端点与密钥'
  if (catalog.error) return `模型清单不可用（${catalog.error}），可直接手填模型名`
  if (!catalog.models.length) return '上游未返回任何模型，可直接手填模型名'
  return `共 ${catalog.models.length} 个可用模型，从下拉选择或直接手填`
})

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
      <label class="af-label">模型来源</label>
      <select
        class="af-select"
        :value="connectionId"
        @change="setSource(($event.target as HTMLSelectElement).value)"
      >
        <option value="">
          进程默认（AF_LLM_*）
        </option>
        <option
          v-for="c in llmConnections ?? []"
          :key="c.id"
          :value="c.id"
        >
          {{ c.name }}{{ c.models.length ? `（${c.models.length} 个模型）` : '' }}
        </option>
      </select>
      <p
        v-if="!(llmConnections ?? []).length"
        class="af-faint"
        style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
      >
        还没有模型连接。到「连接器」页新建一条「模型接入」，填 Base URL 与 API Key 后即可在这里选用。
      </p>

      <template v-if="activeConnection">
        <label class="af-label">model</label>
        <input
          class="af-input mono"
          :value="cfg('model', '')"
          list="af-connection-models"
          :placeholder="activeConnection.models.length ? '从列表选择，或直接填模型名' : '该连接还没有模型清单，填一个模型名'"
          @input="setCfg('model', ($event.target as HTMLInputElement).value)"
        >
        <datalist id="af-connection-models">
          <option
            v-for="m in activeConnection.models"
            :key="m"
            :value="m"
          />
        </datalist>
        <p
          class="af-faint"
          style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
        >
          <template v-if="activeConnection.models.length">
            连接 <span class="mono">{{ activeConnection.name }}</span> 提供 {{ activeConnection.models.length }} 个候选；清单外的模型名也可直接填。
          </template>
          <template v-else>
            连接 <span class="mono">{{ activeConnection.name }}</span> 还没有模型清单，到「连接器」页点「拉取模型」，或在这里直接填。
          </template>
        </p>
        <p
          class="af-faint"
          style="font-size: 11px; line-height: 1.6; margin: 2px 0 0"
        >
          端点与密钥由服务端解密注入，画布只持连接 id。
        </p>
      </template>

      <template v-else>
        <label class="af-label">provider</label>
        <select
          class="af-select"
          :value="cfg('provider', 'openai-compatible')"
          @change="setCfg('provider', ($event.target as HTMLSelectElement).value)"
        >
          <option
            v-if="cfg<string>('provider', '') === 'mock'"
            value="mock"
          >
            mock（遗留节点 · 建议改为真实模型）
          </option>
          <option value="openai-compatible">
            openai-compatible（真实模型）
          </option>
        </select>
        <label class="af-label">model</label>
        <input
          class="af-input mono"
          :value="cfg('model', '')"
          list="af-llm-models"
          placeholder="从列表选择，或直接填模型名"
          @input="setCfg('model', ($event.target as HTMLInputElement).value)"
        >
        <datalist id="af-llm-models">
          <option
            v-for="m in modelOptions"
            :key="m"
            :value="m"
          />
        </datalist>
        <p
          class="af-faint"
          style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
        >
          <template v-if="cfg<string>('provider', '') === 'mock'">
            mock 离线运行，不发起网络请求；换到 openai-compatible 才会真正调用模型。
          </template>
          <template v-else-if="modelWillFallback">
            <span class="af-accent">→ 实际下发：服务端默认模型（AF_LLM_MODEL）</span>；mock-* 名字不会被发送。
          </template>
          <template v-else>
            → 实际下发：<span class="mono af-accent">{{ cfg('model', '') }}</span>
          </template>
        </p>
        <p
          class="af-faint"
          style="font-size: 11px; line-height: 1.6; margin: 2px 0 0"
        >
          {{ modelStatus }}
        </p>
      </template>
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
