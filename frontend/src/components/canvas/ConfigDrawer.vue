<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { approverExpressionError } from '@/dsl/approver-expr'
import type { SubgraphRef } from '@/dsl/subgraph'
import type { DslNode } from '@/dsl/types'
import type { ModelFactsDto } from '@/types/api'
import ModelFactsChips from './ModelFactsChips.vue'

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
    items?: ModelFactsDto[]
  } | null
  /** 已启用且属于本空间的模型连接；选中后模型从该连接的清单里挑。 */
  llmConnections?: {
    id: string
    name: string
    models: string[]
    defaultModel: string
  }[]
  /** 本空间的知识库，供 retrieval 节点勾选文档集。 */
  knowledgeBases?: { id: string; name: string; documentCount: number }[]
}>()

const emit = defineEmits<{
  update: [node: DslNode]
  close: []
  remove: [key: string]
  'edit-subgraph': [ref: SubgraphRef]
  'pick-model': []
  'probe-models': [id: string]
  'open-knowledge': []
  preview: []
}>()

/** 循环 / 并行 / 审批裁剪不出可试跑的小图，入口在这里也不给。 */
const canPreview = computed(
  () =>
    !!props.node
    && !['start', 'end', 'loop', 'parallel', 'human_gate'].includes(props.node.type),
)

/**
 * 只显示与当前节点相关的问题。
 * 子图内的问题 key 形如 `loop_1.body#body_end` / `loop_1#body`，
 * 用「key 前缀」匹配，让 loop / parallel 节点也能看到自己子图里的错。
 */
const nodeIssues = computed(() => {
  if (!props.node) return []
  const key = props.node.key
  return props.issues.filter((i) => {
    const k = i.nodeKey ?? ''
    return k === key || k.startsWith(`${key}.`) || k.startsWith(`${key}#`)
  })
})

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

/** retrieval 节点勾选的知识库 id；数组直接落 DSL，服务端按租户复核归属。 */
const knowledgeIds = computed<string[]>(() => {
  const raw = cfg<unknown>('knowledgeBaseIds', [])
  return Array.isArray(raw) ? raw.map(String) : []
})

function toggleKnowledgeBase(id: string): void {
  const next = knowledgeIds.value.includes(id)
    ? knowledgeIds.value.filter((x) => x !== id)
    : [...knowledgeIds.value, id]
  setCfg('knowledgeBaseIds', next)
}

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

/** 表达式即时体检：与引擎/控制面同一套文法子集，避免「发布才知道写错」。 */
const approverExpressionProblem = computed(() =>
  approvers.value.type === 'EXPRESSION'
    ? approverExpressionError(approvers.value.expression ?? '')
    : null,
)

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

// ---------- 子图：画布入口 + 高级 JSON 编辑（loop.body / parallel.branches） ----------
const jsonDraft = ref<Record<string, string>>({})
const jsonErrors = ref<Record<string, string>>({})

watch(
  () => props.node?.key,
  () => {
    jsonDraft.value = {}
    jsonErrors.value = {}
  },
)

/**
 * 有解析错误时显示用户输入的原文（否则没法继续修），
 * 没有错误时始终从配置序列化——这样从子画布改完回来，文本域自然是最新的。
 */
function jsonText(key: 'body' | 'branches'): string {
  if (jsonErrors.value[key]) return jsonDraft.value[key] ?? ''
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

/** parallel 的每个分支都是独立子图，按钮要按分支逐个给。 */
const parallelBranches = computed<string[]>(() => {
  const raw = cfg<Record<string, unknown>>('branches', {})
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return []
  return Object.keys(raw)
})

function editBranch(branch: string): void {
  if (!props.node) return
  emit('edit-subgraph', { nodeKey: props.node.key, branch })
}

function editBody(): void {
  if (!props.node) return
  emit('edit-subgraph', { nodeKey: props.node.key })
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
      <button
        class="af-btn af-btn--primary af-btn--sm"
        style="width: 100%; justify-content: center"
        @click="emit('pick-model')"
      >
        从常见模型快速接入
      </button>
      <p
        class="af-faint"
        style="font-size: 11px; line-height: 1.6; margin: 6px 0 0"
      >
        选一个常见模型，填一次 API Key 验证通过即可使用；下面的来源、provider 与 model 仍可手动精调。
      </p>

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
        <ModelFactsChips
          :model="cfg('model', '')"
          :items="modelCatalog?.items"
          :current-max-tokens="cfg('maxTokens', 1024)"
          @apply-max-tokens="setCfg('maxTokens', $event)"
        />
        <p
          class="af-faint"
          style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
        >
          <template v-if="activeConnection.models.length">
            连接 <span class="mono">{{ activeConnection.name }}</span> 提供 {{ activeConnection.models.length }} 个候选；清单外的模型名也可直接填。
          </template>
          <template v-else>
            连接 <span class="mono">{{ activeConnection.name }}</span> 还没有模型清单；点右边让服务端用已保存的密钥拉一次（也能顺带验证 Key 是否正确），或直接填模型名。
            <button
              class="af-btn af-btn--sm"
              style="margin-left: 6px"
              @click="emit('probe-models', activeConnection.id)"
            >
              立即拉取模型
            </button>
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
        <ModelFactsChips
          :model="cfg('model', '')"
          :items="modelCatalog?.items"
          :current-max-tokens="cfg('maxTokens', 1024)"
          @apply-max-tokens="setCfg('maxTokens', $event)"
        />
        <p
          class="af-faint"
          style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
        >
          <template v-if="modelWillFallback">
            <span class="af-accent">→ 实际下发：服务端默认模型（AF_LLM_MODEL）</span>；占位模型名不会被发送。
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

    <template v-else-if="node.type === 'retrieval'">
      <p
        class="af-faint"
        style="font-size: 11px; line-height: 1.7; margin: 0 0 10px"
      >
        从勾选的知识库里做真实检索；命中的原文片段写进
        <span class="mono">output.text</span> / <span class="mono">output.results</span>，
        下游 llm 节点的提示词里用 <span class="mono">nodes.xxx.output.text</span> 引用即可。
      </p>
      <label class="af-label">知识库（可多选）</label>
      <div
        v-if="(knowledgeBases ?? []).length"
        style="border: 1px solid var(--af-border); border-radius: var(--af-radius); overflow: hidden"
      >
        <label
          v-for="kb in knowledgeBases ?? []"
          :key="kb.id"
          style="display: flex; align-items: center; gap: 8px; padding: 7px 9px; border-bottom: 1px solid var(--af-border); font-size: 12px; cursor: pointer"
        >
          <input
            type="checkbox"
            :checked="knowledgeIds.includes(kb.id)"
            @change="toggleKnowledgeBase(kb.id)"
          >
          <span>{{ kb.name }}</span>
          <span class="af-spacer" />
          <span class="af-faint">{{ kb.documentCount }} 文档</span>
        </label>
      </div>
      <div
        v-else
        class="af-panel"
        style="padding: 10px; font-size: 12px"
      >
        还没有知识库。先去「知识库」页导入文档，再回来勾选。
        <button
          class="af-btn af-btn--sm"
          style="margin-left: 8px"
          @click="emit('open-knowledge')"
        >
          去导入文档
        </button>
      </div>
      <p
        v-if="!knowledgeIds.length"
        class="af-error-box"
        style="margin-top: 8px"
      >
        至少选择一个知识库，否则这个节点发布时会被校验拦下。
      </p>
      <label class="af-label">queryTemplate（Jinja2 沙箱）</label>
      <textarea
        class="af-textarea"
        rows="3"
        :value="cfg('queryTemplate', '')"
        @input="setCfg('queryTemplate', ($event.target as HTMLTextAreaElement).value)"
      />
      <label class="af-label">topK</label>
      <input
        class="af-input mono"
        type="number"
        min="1"
        max="50"
        :value="cfg('topK', 5)"
        @input="setCfg('topK', Number(($event.target as HTMLInputElement).value))"
      >
      <label class="af-label">minScore（0~1）</label>
      <input
        class="af-input mono"
        type="number"
        step="0.05"
        min="0"
        max="1"
        :value="cfg('minScore', 0)"
        @input="setCfg('minScore', Number(($event.target as HTMLInputElement).value))"
      >
      <p
        class="af-faint"
        style="font-size: 11px; line-height: 1.6"
      >
        评分是本地 BM25 相关度（中文按字与二元组），不调用外部 embedding 服务；调高 minScore 可以只保留强相关片段。
      </p>
    </template>

    <template v-else-if="node.type === 'tool'">
      <label class="af-label">connectorId</label>
      <input
        class="af-input mono"
        :value="cfg('connectorId', '')"
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
        <option value="EXPRESSION">
          按表达式
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
      <template v-else-if="approvers.type === 'EXPRESSION'">
        <label class="af-label">审批人表达式</label>
        <textarea
          class="af-textarea mono"
          rows="3"
          :value="approvers.expression ?? ''"
          placeholder="user.rank >= 3 and user.id != &quot;u-1&quot;"
          @input="patchApprovers({ expression: ($event.target as HTMLTextAreaElement).value })"
        />
        <p
          class="af-faint"
          style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
        >
          挂起瞬间按「空间成员 + 平台管理员」逐个求值，命中者进入审批人快照。
          可用：<code>user.id</code> <code>user.role</code> <code>user.rank</code>
          <code>user.platformAdmin</code> <code>space.id</code>，支持比较、
          <code>in</code>、<code>and/or/not</code> 与列表字面量（不支持函数调用）。
          资源名填错会在发布时被引擎拒绝。
        </p>
        <p
          v-if="approverExpressionProblem"
          class="af-danger"
          style="font-size: 11px; margin: 4px 0 0"
        >
          {{ approverExpressionProblem }}
        </p>
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
      <label class="af-label">body 子图</label>
      <div class="af-subgraph-row">
        <span
          class="af-faint"
          style="font-size: 11px"
        >循环体（无 start，必须有一个 end）</span>
        <button
          class="af-btn af-btn--sm af-btn--primary"
          @click="editBody"
        >
          在画布中编辑
        </button>
      </div>
      <p
        class="af-faint"
        style="font-size: 11px; line-height: 1.6; margin: 4px 0 8px"
      >
        子图内可直接用 {{ `vars.${cfg('itemVar', 'item')}` }} / {{ `vars.${cfg('indexVar', 'index')}` }}，不必声明。
      </p>
      <label class="af-label">高级：直接编辑 body JSON</label>
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
        与子画布双向同步；解析失败时不会写回，草稿保持上一份有效值。
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
      <label class="af-label">branches 子图</label>
      <div
        v-for="b in parallelBranches"
        :key="b"
        class="af-subgraph-row"
      >
        <span class="mono af-dim">{{ b }}</span>
        <button
          class="af-btn af-btn--sm af-btn--primary"
          @click="editBranch(b)"
        >
          在画布中编辑
        </button>
      </div>
      <p
        v-if="!parallelBranches.length"
        class="af-faint"
        style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
      >
        还没有分支。并行节点至少需要两个分支，可在下方 JSON 里补上。
      </p>
      <label class="af-label">高级：直接编辑 branches JSON</label>
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
      <p
        v-else
        class="af-faint"
        style="font-size: 11px; line-height: 1.6; margin: 4px 0 0"
      >
        每个分支一张独立子图（无 start，必须有一个 end）；与子画布双向同步。
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

    <div style="margin-top: 20px; display: flex; gap: 8px">
      <button
        v-if="canPreview"
        class="af-btn af-btn--sm af-btn--primary"
        title="跑这个节点和它的上游，直接看真实输出"
        @click="emit('preview')"
      >
        试跑此节点
      </button>
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

.af-subgraph-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  margin-bottom: 6px;
  background: var(--af-surface);
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius-sm);
}
</style>
