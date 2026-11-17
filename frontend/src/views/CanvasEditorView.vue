<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { VueFlow } from '@vue-flow/core'
import type { Connection, Edge as FlowEdge, Node as FlowNode } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { workflowApi, executionApi } from '@/api'
import { ApiError } from '@/api/http'
import { flowToDsl, dslToFlow, newNodeConfig, AF_NODE_TYPE } from '@/dsl/flow-convert'
import type { FlowEdgeLike, FlowGraph, FlowNodeLike } from '@/dsl/flow-convert'
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
const edges = ref<FlowEdgeLike[]>([])
const variables = ref<Record<string, VariableSpec>>({})
const defaults = ref<DslDefaults>({})
const selectedKey = ref<string | null>(null)
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
    edges.value = v as unknown as FlowEdgeLike[]
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
  issues.value = validateDslLocal(dsl)
}

let keyCounter = 0
function addNode(type: NodeType): void {
  let key = `${type}_${++keyCounter}`
  while (nodes.value.some((n) => n.id === key)) key = `${type}_${++keyCounter}`
  const offset = nodes.value.length * 30
  nodes.value = [
    ...nodes.value,
    {
      id: key,
      type: AF_NODE_TYPE,
      position: { x: 220 + offset, y: 140 + offset },
      data: { nodeType: type, label: key, config: newNodeConfig(type) },
    },
  ]
  selectedKey.value = key
}

function onConnect(conn: Connection): void {
  edges.value = [
    ...edges.value,
    { id: `${conn.source}->${conn.target}`, source: conn.source, target: conn.target },
  ]
}

function onNodeClick(e: unknown): void {
  const id = (e as { node?: { id?: string } }).node?.id
  if (id) selectedKey.value = id
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
  nodes.value = nodes.value.filter((n) => n.id !== key)
  edges.value = edges.value.filter((e) => e.source !== key && e.target !== key)
  selectedKey.value = null
}

// ---------- 自动保存（防抖 1s） ----------

let saveTimer: ReturnType<typeof setTimeout> | null = null
let suppressWatch = true

async function saveNow(): Promise<void> {
  if (saveState.value === 'saving') return
  saveState.value = 'saving'
  error.value = null
  issues.value = validateDslLocal(toDsl())
  try {
    await workflowApi.saveDraft(workflowId, toDsl())
    saveState.value = 'saved'
  } catch (e) {
    saveState.value = 'error'
    if (e instanceof ApiError && e.fieldErrors.length) {
      issues.value = e.fieldErrors.map((f) => ({ nodeKey: f.field, message: f.message }))
    }
    error.value = e instanceof ApiError ? `保存失败 ${e.code}（trace ${e.traceId ?? '-'}）` : '保存失败'
  }
}

watch(
  [nodes, edges, variables, defaults],
  () => {
    if (suppressWatch) return
    issues.value = validateDslLocal(toDsl())
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(() => void saveNow(), 1000)
  },
  { deep: true },
)

function onKeydown(e: KeyboardEvent): void {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    void saveNow()
  } else if (e.key === 'Escape') {
    selectedKey.value = null
    sourceMode.value = false
  } else if ((e.key === 'Delete' || e.key === 'Backspace') && selectedKey.value) {
    const target = e.target as HTMLElement
    if (target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA') {
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
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : '冻结失败'
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
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : '发布失败'
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
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : '触发失败'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  try {
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : '加载失败'
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
      <NodePalette @add="addNode" />
      <div style="flex: 1; position: relative">
        <VueFlow
          v-model:nodes="nodesForFlow"
          v-model:edges="edgesForFlow"
          class="af-flow"
          :delete-key-code="null"
          @connect="onConnect"
          @node-click="onNodeClick"
          @pane-click="selectedKey = null"
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
        @update="updateNode"
        @close="selectedKey = null"
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
