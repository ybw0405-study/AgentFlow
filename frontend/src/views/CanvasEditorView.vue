<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { VueFlow } from '@vue-flow/core'
import type { Connection, Edge as FlowEdge, Node as FlowNode } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { workflowApi, executionApi, llmApi } from '@/api'
import { ApiError } from '@/api/http'
import { errorText } from '@/api/errors'
import { flowToDsl, dslToFlow, newNodeConfig, AF_NODE_TYPE } from '@/dsl/flow-convert'
import { addEdge, removeEdge as removeEdgeOp, removeNode as removeNodeOp, markSelectedEdge } from '@/dsl/graph-ops'
import type { CanvasEdge } from '@/dsl/graph-ops'
import type { FlowGraph, FlowNodeLike } from '@/dsl/flow-convert'
import { validateDslLocal } from '@/dsl/validate'
import type { ValidationIssue } from '@/dsl/validate'
import type { DslDefaults, DslNode, NodeType, VariableSpec, WorkflowDsl } from '@/dsl/types'
import type { WorkflowDefinitionDto } from '@/types/api'
import NodePalette from '@/components/canvas/NodePalette.vue'
import ConfigDrawer from '@/components/canvas/ConfigDrawer.vue'
import VariablesPanel from '@/components/canvas/VariablesPanel.vue'
import DslSourcePanel from '@/components/canvas/DslSourcePanel.vue'
import WorkflowNode from '@/components/canvas/WorkflowNode.vue'

const route = useRoute()
const router = useRouter()
const workflowId = route.params.id as string

const def = ref<WorkflowDefinitionDto | null>(null)
const nodes = ref<FlowNodeLike[]>([])
const edges = ref<CanvasEdge[]>([])
const variables = ref<Record<string, VariableSpec>>({})
const defaults = ref<DslDefaults>({})
const selectedKey = ref<string | null>(null)
const selectedEdgeId = ref<string | null>(null)

let saveTimer: ReturnType<typeof setTimeout> | null = null
let suppressWatch = true
/** 最近一次落盘的 DSL 快照；选中态变化不进 DSL，不应触发保存。 */
let lastSavedJson = ''
/** 保存进行中又有改动时置位，落盘完成后补排一次，避免丢草稿。 */
let savePending = false
const sourceMode = ref(false)
const issues = ref<ValidationIssue[]>([])
const saveState = ref<'saved' | 'saving' | 'error'>('saved')
const notice = ref<string | null>(null)
const error = ref<string | null>(null)
const runDialog = ref(false)
const runVariablesText = ref('{}')
const busy = ref(false)

function toDsl(): WorkflowDsl {
  return flowToDsl({ nodes: nodes.value, edges: edges.value }, variables.value, defaults.value)
}

// Vue Flow 深层泛型与 vue-tsc 的相性差：状态侧用结构化 like-types，模板边界一次性断言
const nodesForFlow = computed({
  get: () => nodes.value as unknown as FlowNode[],
  set: (v) => {
    nodes.value = v as unknown as FlowNodeLike[]
  },
})
const edgesForFlow = computed({
  get: () => edges.value as unknown as FlowEdge[],
  set: (v) => {
    edges.value = v as unknown as CanvasEdge[]
  },
})

const dslForSource = computed<WorkflowDsl>(() => toDsl())

const selectedNode = computed<DslNode | null>(() => {
  const n = nodes.value.find((x) => x.id === selectedKey.value)
  if (!n) return null
  return {
    key: n.id,
    type: n.data.nodeType,
    name: n.data.label === n.id ? undefined : n.data.label,
    timeoutMs: (n.data.config as Record<string, unknown>).timeoutMs as number | undefined,
    config: n.data.config ?? {},
  }
})

const selectedEdgeLabel = computed<string | null>(() => {
  const e = edges.value.find((x) => x.id === selectedEdgeId.value)
  return e ? `${e.source} → ${e.target}` : null
})

const hasStart = computed(() => nodes.value.some((n) => n.data.nodeType === 'start'))

/** 模型清单：只影响配置抽屉的可选项与新建节点默认值，不参与 DSL 校验。 */
const modelCatalog = ref<{
  loaded: boolean
  configured: boolean
  defaultModel: string
  error: string | null
  models: string[]
}>({ loaded: false, configured: false, defaultModel: '', error: null, models: [] })

async function loadModelCatalog(): Promise<void> {
  try {
    const dto = await llmApi.models()
    modelCatalog.value = { loaded: true, ...dto }
  } catch (e) {
    // 清单拿不到不该阻断画布：抽屉会提示手填
    modelCatalog.value = {
      loaded: true,
      configured: false,
      defaultModel: '',
      error: e instanceof ApiError ? e.code : 'UNREACHABLE',
      models: [],
    }
  }
}

async function load(): Promise<void> {
  const detail = await workflowApi.detail(workflowId)
  def.value = detail.definition
  const dslText = detail.definition.draftDsl
    ?? detail.versions.find((v) => v.id === detail.definition.currentVersionId)?.dslJson
  if (dslText) {
    applyDsl(JSON.parse(dslText) as WorkflowDsl)
  }
}

function applyDsl(dsl: WorkflowDsl): void {
  const graph: FlowGraph = dslToFlow(dsl)
  nodes.value = graph.nodes
  edges.value = graph.edges
  variables.value = dsl.variables ?? {}
  defaults.value = dsl.defaults ?? {}
  selectedKey.value = null
  selectedEdgeId.value = null
  issues.value = validateDslLocal(dsl)
  lastSavedJson = JSON.stringify(toDsl())
}

let keyCounter = 0
function addNode(type: NodeType): void {
  if (type === 'start' && hasStart.value) {
    notice.value = 'start 节点只能有一个，请先删除多余的 start 再添加'
    return
  }
  let key = `${type}_${++keyCounter}`
  while (nodes.value.some((n) => n.id === key)) key = `${type}_${++keyCounter}`
  const offset = nodes.value.length * 30
  nodes.value = [
    ...nodes.value,
    {
      id: key,
      type: AF_NODE_TYPE,
      position: { x: 220 + offset, y: 140 + offset },
      data: {
        nodeType: type,
        label: key,
        config: newNodeConfig(type, type === 'llm' ? modelCatalog.value.defaultModel : undefined),
      },
    },
  ]
  selectedKey.value = key
  selectedEdgeId.value = null
}

function onConnect(conn: Connection): void {
  const next = addEdge(edges.value, conn.source, conn.target)
  if (!next) {
    notice.value = `连线 ${conn.source} → ${conn.target} 已存在`
    return
  }
  edges.value = next
}

/** 只有一条选中通道：选中节点或选中连线，二者互斥。 */
function selectNode(id: string): void {
  selectedKey.value = id
  selectedEdgeId.value = null
  if (edges.value.some((e) => e.selected)) {
    edges.value = markSelectedEdge(edges.value, null)
  }
}

function selectEdge(id: string): void {
  selectedEdgeId.value = id
  selectedKey.value = null
  edges.value = markSelectedEdge(edges.value, id)
}

function clearSelection(): void {
  selectedKey.value = null
  selectedEdgeId.value = null
  if (edges.value.some((e) => e.selected)) {
    edges.value = markSelectedEdge(edges.value, null)
  }
}

function onNodeClick(e: unknown): void {
  const id = (e as { node?: { id?: string } }).node?.id
  if (id) selectNode(id)
}

function onEdgeClick(e: unknown): void {
  const id = (e as { edge?: { id?: string } }).edge?.id
  if (id) selectEdge(id)
}

function updateNode(updated: DslNode): void {
  nodes.value = nodes.value.map((n) =>
    n.id === updated.key
      ? {
          ...n,
          data: {
            ...n.data,
            label: updated.name ?? updated.key,
            config: updated.config,
          },
        }
      : n,
  )
}

function removeNode(key: string): void {
  const next = removeNodeOp(nodes.value, edges.value, key)
  nodes.value = next.nodes
  edges.value = next.edges
  if (selectedKey.value === key) selectedKey.value = null
  if (selectedEdgeId.value && !edges.value.some((e) => e.id === selectedEdgeId.value)) {
    selectedEdgeId.value = null
  }
}

function removeEdge(id: string): void {
  edges.value = removeEdgeOp(edges.value, id)
  if (selectedEdgeId.value === id) selectedEdgeId.value = null
}

/** 选中条上的删除入口：连线优先于节点（两者互斥，此处只做兜底）。 */
function removeSelected(): void {
  if (selectedEdgeId.value) removeEdge(selectedEdgeId.value)
  else if (selectedKey.value) removeNode(selectedKey.value)
}

// ---------- 自动保存（防抖 1s） ----------

function dslSnapshot(): string {
  return JSON.stringify(toDsl())
}

function scheduleSave(): void {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => void saveNow(), 1000)
}

async function saveNow(): Promise<void> {
  if (saveState.value === 'saving') {
    savePending = true
    return
  }
  saveState.value = 'saving'
  error.value = null
  const dsl = toDsl()
  issues.value = validateDslLocal(dsl)
  try {
    await workflowApi.saveDraft(workflowId, dsl)
    lastSavedJson = JSON.stringify(dsl)
    saveState.value = 'saved'
  } catch (e) {
    saveState.value = 'error'
    if (e instanceof ApiError && e.fieldErrors.length) {
      issues.value = e.fieldErrors.map((f) => ({ nodeKey: f.field, message: f.message }))
    }
    error.value = errorText(e)
  } finally {
    if (savePending) {
      savePending = false
      if (dslSnapshot() !== lastSavedJson) scheduleSave()
    }
  }
}

watch(
  [nodes, edges, variables, defaults],
  () => {
    if (suppressWatch) return
    issues.value = validateDslLocal(toDsl())
    if (dslSnapshot() === lastSavedJson) return
    scheduleSave()
  },
  { deep: true },
)

function isTextEntry(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el) return false
  const tag = el.tagName
  return (
    tag === 'INPUT' ||
    tag === 'TEXTAREA' ||
    tag === 'SELECT' ||
    el.isContentEditable === true
  )
}

function onKeydown(e: KeyboardEvent): void {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    void saveNow()
  } else if (e.key === 'Escape') {
    clearSelection()
    sourceMode.value = false
  } else if (e.key === 'Delete' || e.key === 'Backspace') {
    if (isTextEntry(e.target)) return
    if (selectedEdgeId.value) {
      e.preventDefault()
      removeEdge(selectedEdgeId.value)
    } else if (selectedKey.value) {
      e.preventDefault()
      removeNode(selectedKey.value)
    }
  }
}

async function freeze(): Promise<string | null> {
  busy.value = true
  error.value = null
  notice.value = null
  try {
    await saveNow()
    const version = await workflowApi.freeze(workflowId)
    notice.value = `已冻结版本 v${version.versionNo}`
    await load()
    return version.id
  } catch (e) {
    error.value = errorText(e)
    return null
  } finally {
    busy.value = false
  }
}

async function freezeAndPublish(): Promise<void> {
  const versionId = await freeze()
  if (!versionId) return
  busy.value = true
  try {
    await workflowApi.publish(workflowId, versionId)
    notice.value = '已发布为当前版本'
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

function openRunDialog(): void {
  const vars: Record<string, unknown> = {}
  for (const [name, spec] of Object.entries(variables.value)) {
    vars[name] = spec.default ?? ''
  }
  runVariablesText.value = JSON.stringify(vars, null, 2)
  runDialog.value = true
}

async function run(): Promise<void> {
  busy.value = true
  error.value = null
  try {
    const variables = JSON.parse(runVariablesText.value) as Record<string, unknown>
    const result = await executionApi.trigger(workflowId, {
      idempotencyKey: crypto.randomUUID(),
      variables,
    })
    void router.push({ name: 'execution-detail', params: { id: result.executionId } })
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  void loadModelCatalog()
  try {
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    setTimeout(() => {
      suppressWatch = false
    }, 0)
  }
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  if (saveTimer) clearTimeout(saveTimer)
})
</script>

<template>
  <div style="display: flex; flex-direction: column; height: 100%">
    <div
      class="af-topbar"
      style="border-bottom: 1px solid var(--af-border)"
    >
      <router-link
        to="/workflows"
        class="af-dim"
      >← 列表</router-link>
      <strong>{{ def?.name ?? '…' }}</strong>
      <span
        class="af-status"
        :class="{ 'af-status--running': def?.status === 'PUBLISHED' }"
      >
        {{ def?.status ?? '…' }}
      </span>
      <span
        v-if="issues.length"
        class="af-danger mono"
        style="font-size: 11px"
      >
        {{ issues.length }} 处校验问题
      </span>
      <div class="af-spacer" />
      <span
        class="af-faint mono"
        style="font-size: 11px"
      >
        {{ saveState === 'saved' ? '已保存' : saveState === 'saving' ? '保存中…' : '保存失败' }}
      </span>
      <button
        class="af-btn af-btn--sm"
        @click="sourceMode = !sourceMode"
      >
        DSL
      </button>
      <router-link :to="{ name: 'versions', params: { id: workflowId } }">
        <button class="af-btn af-btn--sm">版本</button>
      </router-link>
      <button
        class="af-btn af-btn--sm"
        :disabled="busy"
        @click="freeze"
      >
        冻结版本
      </button>
      <button
        class="af-btn af-btn--sm af-btn--primary"
        :disabled="busy"
        @click="freezeAndPublish"
      >
        冻结并发布
      </button>
      <button
        class="af-btn af-btn--sm af-btn--primary"
        :disabled="busy"
        @click="openRunDialog"
      >
        运行
      </button>
    </div>

    <div
      v-if="error"
      class="af-error-box"
      style="margin: 8px 12px 0"
    >
      {{ error }}
    </div>
    <div
      v-if="notice"
      class="af-panel"
      style="margin: 8px 12px 0; padding: 8px 12px; border-color: var(--af-accent-border)"
    >
      <span class="af-accent">{{ notice }}</span>
    </div>

    <div style="flex: 1; display: flex; min-height: 0; position: relative">
      <NodePalette
        :start-taken="hasStart"
        @add="addNode"
      />
      <div style="flex: 1; position: relative">
        <VueFlow
          v-model:nodes="nodesForFlow"
          v-model:edges="edgesForFlow"
          class="af-flow"
          :delete-key-code="null"
          @connect="onConnect"
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @pane-click="clearSelection"
        >
          <Background
            :gap="24"
            pattern-color="var(--af-grid-line)"
          />
          <Controls />
          <template #node-af="props">
            <WorkflowNode v-bind="props" />
          </template>
        </VueFlow>
        <div
          v-if="selectedEdgeId || selectedKey"
          class="af-selection"
        >
          <span class="mono af-dim">{{ selectedEdgeId ? `连线 ${selectedEdgeLabel}` : `节点 ${selectedKey}` }}</span>
          <button
            class="af-btn af-btn--sm af-btn--danger"
            @click="removeSelected"
          >
            删除
          </button>
          <span class="af-faint">Del</span>
        </div>
        <DslSourcePanel
          v-if="sourceMode"
          :dsl="dslForSource"
          @apply="applyDsl"
          @close="sourceMode = false"
        />
      </div>
      <ConfigDrawer
        :node="selectedNode"
        :issues="issues"
        :model-catalog="modelCatalog"
        @update="updateNode"
        @close="clearSelection"
        @remove="removeNode"
      />
    </div>

    <VariablesPanel
      :variables="variables"
      @update="variables = $event"
    />

    <div
      v-if="runDialog"
      class="af-modal-backdrop"
      @click.self="runDialog = false"
    >
      <div class="af-panel af-modal">
        <div
          class="af-page-title"
          style="margin-bottom: 8px"
        >
          运行工作流
        </div>
        <label class="af-label">initialVariables（JSON）</label>
        <textarea
          v-model="runVariablesText"
          class="af-textarea"
          rows="8"
          spellcheck="false"
        />
        <div style="display: flex; gap: 8px; margin-top: 12px; justify-content: flex-end">
          <button
            class="af-btn af-btn--sm"
            @click="runDialog = false"
          >
            取消
          </button>
          <button
            class="af-btn af-btn--sm af-btn--primary"
            :disabled="busy"
            @click="run"
          >
            触发执行
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
.af-flow {
  background: var(--af-bg);
}

.af-flow .vue-flow__edge-path {
  stroke: var(--af-neutral);
  stroke-width: 1.5;
}

.af-flow .vue-flow__edge:hover .vue-flow__edge-path {
  stroke: var(--af-accent);
}

.af-flow .vue-flow__edge.selected .vue-flow__edge-path,
.af-flow .vue-flow__edge.selected:hover .vue-flow__edge-path {
  stroke: var(--af-accent);
  stroke-width: 2.5;
}

.af-selection {
  position: absolute;
  left: 50%;
  bottom: 14px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  background: var(--af-panel);
  border: 1px solid var(--af-border-strong);
  border-radius: var(--af-radius);
  font-size: 12px;
  z-index: 5;
}

.af-flow .vue-flow__controls {
  background: var(--af-panel);
  border: 1px solid var(--af-border);
}

.af-flow .vue-flow__controls-button {
  background: var(--af-panel);
  border-bottom: 1px solid var(--af-border);
  fill: var(--af-text-dim);
}

.af-modal-backdrop {
  position: fixed;
  inset: 0;
  background: var(--af-backdrop);
  display: grid;
  place-items: center;
  z-index: 20;
}

.af-modal {
  width: 480px;
  padding: 18px;
}
</style>
