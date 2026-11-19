<script setup lang="ts">
import { computed, ref, watch } from 'vue'
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
function mappingEntries(key: string): [string, string][] {
  return Object.entries(cfg<Record<string, string>>(key, {}))
}

function setMappingEntry(
  key: string,
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

function addMappingEntry(key: string): void {
  const entries = mappingEntries(key)
  entries.push([`key${entries.length + 1}`, ''])
  setCfg(key, Object.fromEntries(entries))
}

function removeMappingEntry(key: string, index: number): void {
  const entries = mappingEntries(key)
  entries.splice(index, 1)
  setCfg(key, Object.fromEntries(entries))
}

// ---------- condition ----------
interface ConditionCase {
  name: string
  when: string
}

const cases = computed<ConditionCase[]>(() => {
  const raw = cfg<ConditionCase[]>('cases', [])
  return Array.isArray(raw) ? raw : []
})

function setCase(index: number, field: keyof ConditionCase, value: string): void {
  const next = cases.value.map((c, i) => (i === index ? { ...c, [field]: value } : c))
  setCfg('cases', next)
}

function addCase(): void {
  setCfg('cases', [...cases.value, { name: `case${cases.value.length + 1}`, when: 'True' }])
}

function removeCase(index: number): void {
  setCfg('cases', cases.value.filter((_, i) => i !== index))
}

// ---------- transform ----------
interface TransformMapping {
  target: string
  template: string
}

const mappings = computed<TransformMapping[]>(() => {
  const raw = cfg<TransformMapping[]>('mappings', [])
  return Array.isArray(raw) ? raw : []
})

function setMapping(index: number, field: keyof TransformMapping, value: string): void {
  const next = mappings.value.map((m, i) => (i === index ? { ...m, [field]: value } : m))
  setCfg('mappings', next)
}

function addMapping(): void {
  setCfg('mappings', [...mappings.value, { target: 'vars.', template: '' }])
}

function removeMapping(index: number): void {
  setCfg('mappings', mappings.value.filter((_, i) => i !== index))
}

// ---------- human_gate ----------
interface Approvers {
  type: 'ROLE' | 'USERS' | 'EXPRESSION'
  role?: string
  userIds?: string[]
  expression?: string
}

const approvers = computed<Approvers>(
  () => cfg<Approvers>('approvers', { type: 'ROLE', role: 'ADMIN' }) ?? { type: 'ROLE' },
)

function patchApprovers(patch: Partial<Approvers>): void {
  setCfg('approvers', { ...approvers.value, ...patch })
}

const approverUsers = computed(() => (approvers.value.userIds ?? []).join(', '))

function setApproverUsers(raw: string): void {
  const ids = raw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  patchApprovers({ userIds: ids })
}

const onTimeout = computed(
  () => cfg<{ action: string; defaultBranch?: string }>('onTimeout', { action: 'REJECT' }),
)

function patchTimeout(patch: { action?: string; defaultBranch?: string }): void {
  setCfg('onTimeout', { ...onTimeout.value, ...patch })
}

// ---------- 子图 JSON 编辑（loop.body / parallel.branches） ----------
const jsonDraft = ref<Record<string, string>>({})
const jsonErrors = ref<Record<string, string>>({})

watch(
  () => props.node?.key,
  () => {
    jsonDraft.value = {}
    jsonErrors.value = {}
  },
)

function jsonText(key: 'body' | 'branches'): string {
  const draft = jsonDraft.value[key]
  if (draft !== undefined) return draft
  return JSON.stringify(cfg<unknown>(key, null) ?? {}, null, 2)
}

function setJson(key: 'body' | 'branches', raw: string): void {
  jsonDraft.value = { ...jsonDraft.value, [key]: raw }
  try {
    const parsed = JSON.parse(raw) as unknown
    jsonErrors.value = { ...jsonErrors.value, [key]: '' }
    setCfg(key, parsed)
  } catch (e) {
    // 解析失败时保留用户输入，但不写回配置，避免把坏 JSON 存进草稿
    jsonErrors.value = { ...jsonErrors.value, [key]: (e as Error).message }
  }
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

    <template v-else-if="node.type === 'condition'">
      <label class="af-label">cases（命中即走同名出边）</label>
      <div
        v-for="(c, i) in cases"
        :key="i"
        style="display: flex; gap: 6px; margin-bottom: 4px"
      >
        <input
          class="af-input mono"
          :value="c.name"
          placeholder="case 名"
          @input="setCase(i, 'name', ($event.target as HTMLInputElement).value)"
        >
        <input
          class="af-input mono"
          :value="c.when"
          placeholder="表达式，如 vars.n > 3"
          @input="setCase(i, 'when', ($event.target as HTMLInputElement).value)"
        >
        <button
          class="af-btn af-btn--sm"
          title="删除该 case"
          @click="removeCase(i)"
        >
          ×
        </button>
      </div>
      <button
        class="af-btn af-btn--sm"
        @click="addCase()"
      >
        + case
      </button>
      <label class="af-label">elseBranch</label>
      <input
        class="af-input mono"
        :value="cfg('elseBranch', 'default')"
        @input="setCfg('elseBranch', ($event.target as HTMLInputElement).value)"
      >
      <p
        class="af-faint"
        style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
      >
        when 由受限表达式求值（可用 vars.* / nodes.*，无 import / eval）。出边属于哪条分支，在画布底部选中连线后设置。
      </p>
    </template>

    <template v-else-if="node.type === 'transform'">
      <label class="af-label">mappings（target 写回 vars.*）</label>
      <div
        v-for="(m, i) in mappings"
        :key="i"
        style="display: flex; gap: 6px; margin-bottom: 4px"
      >
        <input
          class="af-input mono"
          :value="m.target"
          placeholder="vars.summary"
          @input="setMapping(i, 'target', ($event.target as HTMLInputElement).value)"
        >
        <input
          class="af-input mono"
          :value="m.template"
          placeholder="{{ vars.x }}"
          @input="setMapping(i, 'template', ($event.target as HTMLInputElement).value)"
        >
        <button
          class="af-btn af-btn--sm"
          title="删除该映射"
          @click="removeMapping(i)"
        >
          ×
        </button>
      </div>
      <button
        class="af-btn af-btn--sm"
        @click="addMapping()"
      >
        + 映射
      </button>
      <p
        class="af-faint"
        style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
      >
        target 是变量定义，不必先在工作流变量里声明；template 走 Jinja2 沙箱。
      </p>
    </template>

    <template v-else-if="node.type === 'human_gate'">
      <label class="af-label">审批人规则</label>
      <select
        class="af-select"
        :value="approvers.type"
        @change="patchApprovers({ type: ($event.target as HTMLSelectElement).value as Approvers['type'] })"
      >
        <option value="ROLE">
          按角色
        </option>
        <option value="USERS">
          按用户
        </option>
      </select>
      <template v-if="approvers.type === 'ROLE'">
        <label class="af-label">角色</label>
        <select
          class="af-select"
          :value="approvers.role ?? 'ADMIN'"
          @change="patchApprovers({ role: ($event.target as HTMLSelectElement).value })"
        >
          <option value="OWNER">
            OWNER
          </option>
          <option value="ADMIN">
            ADMIN
          </option>
          <option value="EDITOR">
            EDITOR
          </option>
          <option value="VIEWER">
            VIEWER
          </option>
        </select>
      </template>
      <template v-else-if="approvers.type === 'USERS'">
        <label class="af-label">用户 id（逗号分隔）</label>
        <input
          class="af-input mono"
          :value="approverUsers"
          placeholder="u-editor, u-admin"
          @input="setApproverUsers(($event.target as HTMLInputElement).value)"
        >
      </template>
      <label class="af-label">提示词</label>
      <textarea
        class="af-textarea"
        rows="4"
        :value="cfg('promptTemplate', '')"
        @input="setCfg('promptTemplate', ($event.target as HTMLTextAreaElement).value)"
      />
      <label class="af-label">超时（毫秒，最短 60000）</label>
      <input
        class="af-input mono"
        type="number"
        :value="cfg('timeoutMs', 86400000)"
        @input="setCfg('timeoutMs', Number(($event.target as HTMLInputElement).value))"
      >
      <label class="af-label">超时动作</label>
      <select
        class="af-select"
        :value="onTimeout.action"
        @change="patchTimeout({ action: ($event.target as HTMLSelectElement).value })"
      >
        <option value="REJECT">
          自动驳回
        </option>
        <option value="DEFAULT_BRANCH">
          走默认分支
        </option>
      </select>
      <template v-if="onTimeout.action === 'DEFAULT_BRANCH'">
        <label class="af-label">默认分支名</label>
        <input
          class="af-input mono"
          :value="onTimeout.defaultBranch ?? ''"
          placeholder="approved"
          @input="patchTimeout({ defaultBranch: ($event.target as HTMLInputElement).value })"
        >
      </template>
      <p
        class="af-faint"
        style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
      >
        审批人快照在发起时冻结；出边只能连 approved / rejected 两条分支。审批节点不能放进 loop / parallel 子图。
      </p>
    </template>

    <template v-else-if="node.type === 'subworkflow'">
      <label class="af-label">definitionId</label>
      <input
        class="af-input mono"
        :value="cfg('definitionId', '')"
        placeholder="被调用工作流的 id"
        @input="setCfg('definitionId', ($event.target as HTMLInputElement).value)"
      >
      <label class="af-label">版本策略</label>
      <select
        class="af-select"
        :value="cfg('versionPolicy', 'LATEST_PUBLISHED')"
        @change="setCfg('versionPolicy', ($event.target as HTMLSelectElement).value)"
      >
        <option value="LATEST_PUBLISHED">
          LATEST_PUBLISHED（跟随最新发布）
        </option>
        <option value="FIXED">
          FIXED（锁定版本）
        </option>
      </select>
      <template v-if="cfg<string>('versionPolicy', 'LATEST_PUBLISHED') === 'FIXED'">
        <label class="af-label">versionId</label>
        <input
          class="af-input mono"
          :value="cfg('versionId', '')"
          @input="setCfg('versionId', ($event.target as HTMLInputElement).value)"
        >
      </template>
      <label class="af-label">inputMapping</label>
      <div
        v-for="([k, v], i) in mappingEntries('inputMapping')"
        :key="`in-${i}`"
        style="display: flex; gap: 6px; margin-bottom: 4px"
      >
        <input
          class="af-input mono"
          :value="k"
          placeholder="目标变量"
          @input="setMappingEntry('inputMapping', i, 'k', ($event.target as HTMLInputElement).value)"
        >
        <input
          class="af-input mono"
          :value="v"
          placeholder="{{ vars.x }}"
          @input="setMappingEntry('inputMapping', i, 'v', ($event.target as HTMLInputElement).value)"
        >
        <button
          class="af-btn af-btn--sm"
          @click="removeMappingEntry('inputMapping', i)"
        >
          ×
        </button>
      </div>
      <button
        class="af-btn af-btn--sm"
        @click="addMappingEntry('inputMapping')"
      >
        + 入参
      </button>
      <label class="af-label">outputMapping</label>
      <div
        v-for="([k, v], i) in mappingEntries('outputMapping')"
        :key="`out-${i}`"
        style="display: flex; gap: 6px; margin-bottom: 4px"
      >
        <input
          class="af-input mono"
          :value="k"
          placeholder="输出名"
          @input="setMappingEntry('outputMapping', i, 'k', ($event.target as HTMLInputElement).value)"
        >
        <input
          class="af-input mono"
          :value="v"
          placeholder="{{ nodes.sub.output.x }}"
          @input="setMappingEntry('outputMapping', i, 'v', ($event.target as HTMLInputElement).value)"
        >
        <button
          class="af-btn af-btn--sm"
          @click="removeMappingEntry('outputMapping', i)"
        >
          ×
        </button>
      </div>
      <button
        class="af-btn af-btn--sm"
        @click="addMappingEntry('outputMapping')"
      >
        + 出参
      </button>
    </template>

    <template v-else-if="node.type === 'loop'">
      <label class="af-label">itemsExpression</label>
      <input
        class="af-input mono"
        :value="cfg('itemsExpression', '[]')"
        placeholder="vars.items"
        @input="setCfg('itemsExpression', ($event.target as HTMLInputElement).value)"
      >
      <label class="af-label">itemVar / indexVar</label>
      <div style="display: flex; gap: 6px">
        <input
          class="af-input mono"
          :value="cfg('itemVar', 'item')"
          @input="setCfg('itemVar', ($event.target as HTMLInputElement).value)"
        >
        <input
          class="af-input mono"
          :value="cfg('indexVar', 'index')"
          @input="setCfg('indexVar', ($event.target as HTMLInputElement).value)"
        >
      </div>
      <label class="af-label">maxIterations / concurrency</label>
      <div style="display: flex; gap: 6px">
        <input
          class="af-input mono"
          type="number"
          min="1"
          max="1000"
          :value="cfg('maxIterations', 100)"
          @input="setCfg('maxIterations', Number(($event.target as HTMLInputElement).value))"
        >
        <input
          class="af-input mono"
          type="number"
          min="1"
          max="16"
          :value="cfg('concurrency', 1)"
          @input="setCfg('concurrency', Number(($event.target as HTMLInputElement).value))"
        >
      </div>
      <label class="af-label">body 子图（JSON）</label>
      <textarea
        class="af-textarea mono"
        rows="10"
        spellcheck="false"
        :value="jsonText('body')"
        @input="setJson('body', ($event.target as HTMLTextAreaElement).value)"
      />
      <p
        v-if="jsonErrors.body"
        class="af-accent"
        style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
      >
        JSON 未生效：{{ jsonErrors.body }}
      </p>
      <p
        v-else
        class="af-faint"
        style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
      >
        子图内可直接用 {{ `vars.${cfg('itemVar', 'item')}` }} / {{ `vars.${cfg('indexVar', 'index')}` }}，不必声明。
      </p>
    </template>

    <template v-else-if="node.type === 'parallel'">
      <label class="af-label">join</label>
      <select
        class="af-select"
        :value="cfg('join', 'all')"
        @change="setCfg('join', ($event.target as HTMLSelectElement).value)"
      >
        <option value="all">
          all（全部分支成功）
        </option>
        <option value="first_success">
          first_success（首个成功即返回）
        </option>
      </select>
      <label class="af-label">maxConcurrency</label>
      <input
        class="af-input mono"
        type="number"
        min="1"
        max="16"
        :value="cfg('maxConcurrency', 4)"
        @input="setCfg('maxConcurrency', Number(($event.target as HTMLInputElement).value))"
      >
      <label class="af-label">branches 子图（JSON，至少两个）</label>
      <textarea
        class="af-textarea mono"
        rows="12"
        spellcheck="false"
        :value="jsonText('branches')"
        @input="setJson('branches', ($event.target as HTMLTextAreaElement).value)"
      />
      <p
        v-if="jsonErrors.branches"
        class="af-accent"
        style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
      >
        JSON 未生效：{{ jsonErrors.branches }}
      </p>
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
