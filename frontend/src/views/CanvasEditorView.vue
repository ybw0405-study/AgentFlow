<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { VueFlow } from '@vue-flow/core'
import type { Connection, Edge as FlowEdge, Node as FlowNode } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { workflowApi, executionApi, llmApi, connectorApi, knowledgeApi } from '@/api'
import { ApiError } from '@/api/http'
import { errorText } from '@/api/errors'
import { bindConnectionToLlmNodes, inspectLegacyDsl } from '@/dsl/legacy-dsl'
import { flowToDsl, dslToFlow, newNodeConfig, AF_NODE_TYPE } from '@/dsl/flow-convert'
import { addEdge, removeEdge as removeEdgeOp, removeNode as removeNodeOp, markSelectedEdge, setEdgeBranch } from '@/dsl/graph-ops'
import type { CanvasEdge } from '@/dsl/graph-ops'
import type { FlowNodeLike } from '@/dsl/flow-convert'
import { MAX_SUBGRAPH_DEPTH, validateDslLocal } from '@/dsl/validate'
import type { ValidationIssue } from '@/dsl/validate'
import { initialVariables } from '@/dsl/run-defaults'
import { missingRequired } from '@/dsl/run-input'
import { connectionProblem } from '@/dsl/connection'
import { autoLayout } from '@/dsl/auto-layout'
import NodeSearch from '@/components/canvas/NodeSearch.vue'
import { WORKFLOW_TEMPLATES } from '@/dsl/templates'
import type { WorkflowTemplate } from '@/dsl/templates'
import {
  readSubgraphPath,
  subgraphRemovalError,
  writeSubgraphPath,
} from '@/dsl/subgraph'
import type { SubgraphRef } from '@/dsl/subgraph'
import type { DslDefaults, DslNode, NodeType, VariableSpec, WorkflowDsl } from '@/dsl/types'
import type { LlmDefaults } from '@/dsl/types'
import { branchOptions } from '@/dsl/types'
import type {
  WorkflowDefinitionDto,
  LlmConnectionConfig,
  KnowledgeBaseDto,
  ProbeModelItem,
} from '@/types/api'
import type { ModelFactsDto } from '@/types/api'
import NodePalette from '@/components/canvas/NodePalette.vue'
import ConfigDrawer from '@/components/canvas/ConfigDrawer.vue'
import VariablesPanel from '@/components/canvas/VariablesPanel.vue'
import DslSourcePanel from '@/components/canvas/DslSourcePanel.vue'
import WorkflowNode from '@/components/canvas/WorkflowNode.vue'
import ModelHub from '@/components/canvas/ModelHub.vue'
import NodePreviewPanel from '@/components/canvas/NodePreviewPanel.vue'
import RunVariablesForm from '@/components/canvas/RunVariablesForm.vue'
import WorkflowDefaultsDialog from '@/components/canvas/WorkflowDefaultsDialog.vue'
import { displayModelName } from '@/dsl/model-presets'
import { probeErrorMessage, withProbeItems } from '@/dsl/model-connection'
import type { ConnectModelResult } from '@/dsl/model-connection'

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
/** 运行弹窗里的入参：按变量声明类型填写，避免手写 JSON 出错。 */
const runVariables = ref<Record<string, unknown>>({})
/** 正在试跑的节点 key；为空表示面板关闭。 */
const previewKey = ref<string | null>(null)
const defaultsOpen = ref(false)
const busy = ref(false)
/** 双击画布 / 空格唤起的节点搜索，以及单节点复制粘贴。 */
const searchOpen = ref(false)
const clipboardNode = ref<FlowNodeLike | null>(null)
const templateOpen = ref(false)
const pendingTemplate = ref<WorkflowTemplate | null>(null)
/** 从节点卡片 / 配置抽屉唤起的模型接入向导；值为目标节点 id。 */
const modelHubFor = ref<string | null>(null)
/** 本空间的知识库，供 retrieval 节点选择文档集。 */
const knowledgeBases = ref<{ id: string; name: string; documentCount: number }[]>([])

async function loadKnowledgeBases(): Promise<void> {
  try {
    const rows: KnowledgeBaseDto[] = await knowledgeApi.bases()
    knowledgeBases.value = rows.map((b) => ({
      id: b.id,
      name: b.name,
      documentCount: b.documentCount,
    }))
  } catch {
    // 知识库列表只影响 retrieval 的可选项，拿不到不该阻断画布
    knowledgeBases.value = []
  }
}

/** 抽屉里的「立即拉取模型」：复用连接器页同一套探测 + 写回 config 的流程。 */
async function probeConnectionModels(id: string): Promise<void> {
  try {
    const probe = await connectorApi.probeModels(id)
    if (!probe.ok || !probe.models.length) {
      notice.value = `没能连上：${probeErrorMessage(probe.errorCode)}`
      return
    }
    const rows = await connectorApi.list()
    const row = rows.find((c) => c.id === id)
    const cfg = (row?.config ?? {}) as LlmConnectionConfig
    const keep = cfg.defaultModel && probe.models.includes(cfg.defaultModel)
    await connectorApi.update(id, {
      config: {
        ...withProbeItems(cfg, probe.items),
        models: probe.models,
        defaultModel: keep ? cfg.defaultModel : probe.models[0],
      },
    })
    await loadLlmConnections()
    const facts = probe.items.filter((i) => i.contextWindow || i.maxOutputTokens).length
    notice.value = facts
      ? `连接可用，已拉取 ${probe.models.length} 个模型（其中 ${facts} 个带上游能力字段）`
      : `连接可用，已拉取 ${probe.models.length} 个模型；现在可在 model 下拉里选`
  } catch (e) {
    notice.value = `没能连上：${errorText(e)}`
  }
}

function goKnowledge(): void {
  void router.push('/knowledge')
}

/** 内置示例的 llm 节点全是 mock：一键整层挂到第一条可用连接，不必逐节点改。 */
function applyConnectionToAllLlm(): void {
  const target = llmConnections.value[0]
  if (!target) {
    quickConnectDemoModel()
    return
  }
  if (!target.defaultModel) {
    notice.value = `「${target.name}」还没有默认模型：点「立即拉取模型」，或到「连接器」页设置后再切`
    return
  }
  let count = 0
  for (const n of nodes.value) {
    if (n.data.nodeType !== 'llm') continue
    patchNodeConfig(n.id, {
      connectionId: target.id,
      provider: 'openai-compatible',
      model: target.defaultModel,
    })
    count += 1
  }
  notice.value = `已把 ${count} 个 llm 节点切到「${target.name}」，点「运行」即按当前草稿走该连接`
}

/** 演示态提示条上的一步到位入口：直接把第一个 llm 节点带进模型接入向导。 */
function quickConnectDemoModel(): void {
  const target = nodes.value.find((n) => n.data.nodeType === 'llm')
  if (!target) {
    notice.value = '画布上还没有 llm 节点，先从左侧添加一个'
    return
  }
  selectNode(target.id)
  openModelHub(target.id)
}
/** 运行弹窗里标出必填项，其余是流程内部变量，保持默认即可。 */
const requiredVariableNames = computed(() =>
  Object.entries(variables.value)
    .filter(([, spec]) => spec.required)
    .map(([name]) => name)
    .join('、') || '无',
)

const runMissing = computed(() => missingRequired(variables.value, runVariables.value))

/** 根层 DSL：唯一真源。画布上正在编辑的可能是它内部的某一层子图。 */
const rootDsl = ref<WorkflowDsl | null>(null)
/** 当前画布所在位置；空数组表示根层，每项是相对上一层的子图引用。 */
const path = ref<SubgraphRef[]>([])
const inSubgraph = computed(() => path.value.length > 0)

/** 当前画布这一层对应的 DSL（不含子图写回）。 */
function layerDsl(): WorkflowDsl {
  return flowToDsl({ nodes: nodes.value, edges: edges.value }, variables.value, defaults.value)
}

/** 演示态：整图没有真实模型 / 文档检索时，把「输出是示例文本」摆在明面上。 */
const legacyState = computed(() => inspectLegacyDsl(layerDsl()))

/**
 * 把「画布当前层」合并回整份 DSL。
 * 子图编辑时只替换当前层的 nodes/edges，外层内容与根层变量原样保留，
 * 这样自动保存 / 版本对比拿到的始终是完整 DSL，而不是一张局部图。
 */
function toDsl(): WorkflowDsl {
  const layer = layerDsl()
  const base = rootDsl.value
  if (!base || !path.value.length) return layer
  const written = writeSubgraphPath(base, path.value, {
    nodes: layer.nodes,
    edges: layer.edges,
  })
  // 变量与 defaults 只在根层维护（变量面板始终编辑根层），子图内也要同步过去
  return { ...written, variables: variables.value, defaults: defaults.value }
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

// VueFlow 通过 expose 把实例方法挂到模板 ref 上（fitView / setViewport 等）。
// 只声明这里用到的那一个，避免把整份实例类型引进来和 vue-tsc 打架。
const flowRef = ref<{ fitView?: (opts?: { padding?: number }) => Promise<boolean> } | null>(null)

/**
 * 装载一层图之后把整张图放进视口。
 *
 * 位置是 DSL 里存的绝对坐标，12 个节点的流程很容易比视口宽——不 fit 的话首屏只看到
 * 前几个节点，末尾的 end 被裁在视口外（看起来像“图坏了”）。等一帧再 fit：节点尺寸
 * 要等组件渲染完才量得到，早一拍 fit 会按 0 尺寸算，结果仍是歪的。
 */
function fitViewSoon(): void {
  void nextTick(() => {
    requestAnimationFrame(() => {
      void flowRef.value?.fitView?.({ padding: 0.15 })
    })
  })
}

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

/** 选中连线的源节点：只有分支节点才允许给它挂 branch。 */
const selectedEdgeSource = computed(() => {
  const e = edges.value.find((x) => x.id === selectedEdgeId.value)
  if (!e) return null
  const n = nodes.value.find((x) => x.id === e.source)
  if (!n) return null
  return { type: n.data.nodeType, config: (n.data.config ?? {}) as Record<string, unknown> }
})

const edgeBranchOptions = computed<string[]>(() =>
  selectedEdgeSource.value ? branchOptions(selectedEdgeSource.value) : [],
)

const selectedEdgeBranch = computed<string>(() => {
  const e = edges.value.find((x) => x.id === selectedEdgeId.value)
  return typeof e?.label === 'string' ? e.label : ''
})

function setSelectedEdgeBranch(value: string): void {
  const current = edges.value.find((e) => e.id === selectedEdgeId.value)
  if (!current) return
  const next = setEdgeBranch(edges.value, current.id, value)
  if (!next) {
    notice.value = `该分支名已被另一条连线占用：${value || '（默认）'}`
    return
  }
  const moved = next.find((e) => e.source === current.source && e.target === current.target)
  edges.value = next
  selectedEdgeId.value = moved?.id ?? null
}

const hasStart = computed(() => nodes.value.some((n) => n.data.nodeType === 'start'))

/** 模型清单：只影响配置抽屉的可选项与新建节点默认值，不参与 DSL 校验。 */
const modelCatalog = ref<{
  loaded: boolean
  configured: boolean
  defaultModel: string
  error: string | null
  models: string[]
  items?: ModelFactsDto[]
}>({ loaded: false, configured: false, defaultModel: '', error: null, models: [] })

/** 模型连接：LLM 节点可以选用某条连接托管的端点与密钥，而不是进程级 AF_LLM_*。 */
const llmConnections = ref<{
  id: string
  name: string
  models: string[]
  /** 上次探测到的能力字段；连接建得早时可能为空，界面退回按模型名推断。 */
  modelItems: ProbeModelItem[]
  defaultModel: string
}[]>([])

async function loadLlmConnections(): Promise<void> {
  try {
    const rows = await connectorApi.list()
    llmConnections.value = rows
      .filter((c) => c.type === 'LLM' && c.enabled)
      .map((c) => {
        const cfg = (c.config ?? {}) as LlmConnectionConfig
        const models = Array.isArray(cfg.models) ? cfg.models : []
        return {
          id: c.id,
          name: c.name,
          models,
          modelItems: Array.isArray(cfg.modelItems) ? cfg.modelItems : [],
          defaultModel: cfg.defaultModel ?? models[0] ?? '',
        }
      })
  } catch {
    // 连接列表拿不到只影响可选项，画布仍可用进程级模型
    llmConnections.value = []
  }
}

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
  rootDsl.value = dsl
  path.value = []
  loadLayer()
  lastSavedJson = JSON.stringify(toDsl())
}

/** 从 rootDsl 重新装载当前层；路径失效（例如 JSON 里删掉了 body）时退回根层。 */
function loadLayer(): void {
  const base = rootDsl.value
  if (!base) return
  const graph = path.value.length
    ? readSubgraphPath(base, path.value)
    : { nodes: base.nodes, edges: base.edges }
  if (!graph) {
    path.value = []
    notice.value = '子图已不存在，已回到工作流根层'
    loadLayer()
    return
  }
  const flow = dslToFlow({ ...base, nodes: graph.nodes, edges: graph.edges })
  nodes.value = flow.nodes
  edges.value = flow.edges
  variables.value = base.variables ?? {}
  defaults.value = base.defaults ?? {}
  selectedKey.value = null
  selectedEdgeId.value = null
  issues.value = validateDslLocal(toDsl())
  fitViewSoon()
}

interface Crumb {
  label: string
  /** 点击后保留的路径长度；等于当前长度即当前层。 */
  level: number
}

const crumbs = computed<Crumb[]>(() => {
  // 一层一个可点项：`loop_1.body` / `parallel_1.a`。节点 key 单独成项会让人以为
  // 「点 loop_1 能看节点本身」，但子图和节点的编辑面不是同一个，索性合成一项。
  const out: Crumb[] = [{ label: '工作流', level: 0 }]
  path.value.forEach((ref, i) => {
    out.push({
      label: ref.branch ? `${ref.nodeKey}.${ref.branch}` : `${ref.nodeKey}.body`,
      level: i + 1,
    })
  })
  return out
})

/** 进入子图：先把当前层写回根 DSL，再切换画布内容。 */
function enterSubgraph(ref: SubgraphRef): void {
  rootDsl.value = toDsl()
  const nextPath = [...path.value, ref]
  // 与前端即时校验、引擎发布校验同一口径：第 5 层不再进入，也不让它悄悄
  // 变成「能编辑但发布被拒」的数据。
  if (nextPath.length > MAX_SUBGRAPH_DEPTH) {
    notice.value = `子图最多嵌套 ${MAX_SUBGRAPH_DEPTH} 层，无法继续下钻`
    return
  }
  if (!readSubgraphPath(rootDsl.value, nextPath)) {
    notice.value = `子图 ${ref.nodeKey}${ref.branch ? `.${ref.branch}` : '.body'} 不可编辑（结构缺失）`
    return
  }
  path.value = nextPath
  sourceMode.value = false
  loadLayer()
}

/** 面包屑回上层：level 是目标路径长度。 */
function exitTo(level: number): void {
  if (level >= path.value.length) return
  rootDsl.value = toDsl()
  path.value = path.value.slice(0, level)
  loadLayer()
}

/** 当前层的可读标题，用于提示与空态文案。 */
const layerTitle = computed<string>(() => {
  if (!path.value.length) return '工作流根层'
  return path.value
    .map((ref) => (ref.branch ? `${ref.nodeKey}.${ref.branch}` : `${ref.nodeKey}.body`))
    .join(' > ')
})

let keyCounter = 0

/** 新建 llm 节点用哪套默认值：工作流默认模型 > 首条连接 > 服务端默认模型。 */
function newLlmDefaults(): { model?: string; connection: { id: string; defaultModel: string } | null } {
  const configured = defaults.value.llm
  if (configured?.model) {
    const connection = configured.connectionId
      ? llmConnections.value.find((c) => c.id === configured.connectionId) ?? null
      : null
    return { model: configured.model, connection }
  }
  return { connection: llmConnections.value[0] ?? null }
}

function addNode(type: NodeType): void {
  if (type === 'start' && inSubgraph.value) {
    notice.value = '子图内不能创建 start 节点：进入子图就是它的入口'
    return
  }
  if (type === 'start' && hasStart.value) {
    notice.value = 'start 节点只能有一个，请先删除多余的 start 再添加'
    return
  }
  // key 只需在本层唯一：不同子图里出现同名节点是合法的，不必和外层一起判重
  let key = `${type}_${++keyCounter}`
  while (nodes.value.some((n) => n.id === key)) key = `${type}_${++keyCounter}`
  const offset = nodes.value.length * 30
  const llmDefault = type === 'llm' ? newLlmDefaults() : null
  const config = newNodeConfig(
    type,
    llmDefault?.model ?? (type === 'llm' ? modelCatalog.value.defaultModel : undefined),
    llmDefault?.connection ?? null,
    type === 'retrieval' ? knowledgeBases.value[0]?.id : undefined,
  )
  // 工作流默认模型优先于连接自带的默认模型：连接只提供端点，模型按默认值来
  if (llmDefault?.model) {
    config.model = llmDefault.model
    config.connectionId = llmDefault.connection?.id
  }
  nodes.value = [
    ...nodes.value,
    {
      id: key,
      type: AF_NODE_TYPE,
      position: { x: 220 + offset, y: 140 + offset },
      data: {
        nodeType: type,
        label: key,
        config,
      },
    },
  ]
  selectedKey.value = key
  selectedEdgeId.value = null
}

function onConnect(conn: Connection): void {
  const problem = connectionProblem(nodes.value, edges.value, conn, inSubgraph.value)
  if (problem) {
    notice.value = problem
    return
  }
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

/** 节点卡片内联编辑：只改顶层字段，不重建节点，也不触碰嵌套结构。 */
function patchNodeConfig(id: string, patch: Record<string, unknown>): void {
  nodes.value = nodes.value.map((n) =>
    n.id === id
      ? { ...n, data: { ...n.data, config: { ...(n.data.config ?? {}), ...patch } } }
      : n,
  )
}

/** 卡片上的「展开完整配置」把右侧抽屉带到该节点（抽屉与 DSL 视图保持原样）。 */
function openFullConfig(id: string): void {
  selectNode(id)
}

function currentModelLabel(id: string | null): string {
  if (!id) return ''
  const node = nodes.value.find((n) => n.id === id)
  return node ? displayModelName((node.data.config ?? {}) as Record<string, unknown>) : ''
}

/** 点卡片上的「模型」或抽屉里的快速入口，就地打开模型清单 + 连接向导。 */
function openModelHub(id: string): void {
  modelHubFor.value = id
}

function closeModelHub(): void {
  modelHubFor.value = null
}

/** 向导验证通过：把 connectionId / model 写回节点，节点即刻可用，无需再去连接器页。 */
async function onModelConnected(result: ConnectModelResult): Promise<void> {
  const id = modelHubFor.value
  closeModelHub()
  if (id) {
    patchNodeConfig(id, {
      connectionId: result.connectorId,
      model: result.model,
      provider: 'openai-compatible',
    })
    await loadLlmConnections()
  }
  notice.value = result.warning
    ? `已接入「${result.name}」：${result.warning}`
    : `已接入「${result.name}」，节点模型 ${result.model} 立即可用`
}

/** 不建画布连接时，改用启动脚本注入的进程级端点（AF_LLM_*）。 */
function useBuiltinModel(): void {
  const id = modelHubFor.value
  closeModelHub()
  if (id) {
    patchNodeConfig(id, {
      connectionId: undefined,
      provider: 'openai-compatible',
      model: modelCatalog.value.defaultModel || 'deepseek-chat',
    })
    notice.value = '已改用进程默认端点（AF_LLM_*），调用走服务端配置的真实模型'
  }
}

function goConnectors(): void {
  closeModelHub()
  void router.push('/connectors')
}

/** 搜索落位：新建后按需自动接到落位前选中的节点，省掉一次手动连线。 */
function addFromSearch(type: NodeType, connect: boolean): void {
  const anchor = selectedKey.value
  addNode(type)
  const created = selectedKey.value
  if (connect && anchor && created && anchor !== created) {
    const problem = connectionProblem(
      nodes.value,
      edges.value,
      { source: anchor, target: created },
      inSubgraph.value,
    )
    if (!problem) {
      const next = addEdge(edges.value, anchor, created)
      if (next) edges.value = next
    } else {
      notice.value = problem
    }
  }
  searchOpen.value = false
}

function tidyLayout(): void {
  nodes.value = autoLayout(nodes.value, edges.value)
  fitViewSoon()
  notice.value = '已按依赖层级整理布局'
}

function copySelected(): void {
  const node = nodes.value.find((n) => n.id === selectedKey.value)
  if (!node) {
    notice.value = '先选中一个节点再复制'
    return
  }
  clipboardNode.value = JSON.parse(JSON.stringify(node)) as FlowNodeLike
  notice.value = `已复制节点 ${node.id}`
}

function pasteNode(): void {
  const source = clipboardNode.value
  if (!source) {
    notice.value = '剪贴板里没有节点'
    return
  }
  if (source.data.nodeType === 'start') {
    notice.value = 'start 节点只能有一个，不能粘贴'
    return
  }
  let key = `${source.data.nodeType}_${++keyCounter}`
  while (nodes.value.some((n) => n.id === key)) key = `${source.data.nodeType}_${++keyCounter}`
  nodes.value = [
    ...nodes.value,
    {
      ...(JSON.parse(JSON.stringify(source)) as FlowNodeLike),
      id: key,
      position: { x: source.position.x + 48, y: source.position.y + 48 },
      data: { ...source.data, label: key },
    },
  ]
  selectNode(key)
}

function onPaneDblClick(e: MouseEvent): void {
  const target = e.target as HTMLElement | null
  if (target?.classList?.contains('vue-flow__pane')) searchOpen.value = true
}

function openTemplates(): void {
  pendingTemplate.value = null
  templateOpen.value = true
}

function closeTemplates(): void {
  templateOpen.value = false
  pendingTemplate.value = null
}

/** 模板只替换草稿：已冻结/发布的版本是不可变的，不受影响。 */
function confirmTemplate(): void {
  const template = pendingTemplate.value
  if (!template) return
  const dsl = JSON.parse(JSON.stringify(template.dsl)) as WorkflowDsl
  const conn = llmConnections.value[0]
  // 模板默认走进程级端点；若空间已有连接，则把整层 llm 节点挂到该连接。
  const bound = conn
    ? bindConnectionToLlmNodes(dsl, { id: conn.id, defaultModel: conn.defaultModel })
    : 0
  applyDsl(dsl)
  void saveNow()
  closeTemplates()
  notice.value = bound
    ? `已应用模板「${template.name}」，并把 ${bound} 个 llm 节点接到「${conn?.name}」`
    : `已应用模板「${template.name}」，草稿已保存；模型走进程默认端点（AF_LLM_*）`
  fitViewSoon()
}

function removeNode(key: string): void {
  // 整图必须留一个 end 收口；子图不受此限（出口由上层 loop / parallel 决定）。
  const reason = subgraphRemovalError(
    {
      nodes: nodes.value.map((n) => ({
        key: n.id,
        type: n.data.nodeType,
        config: n.data.config ?? {},
      })),
      edges: [],
    },
    key,
    !inSubgraph.value,
  )
  if (reason) {
    notice.value = reason
    return
  }
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
  if (defaultsOpen.value) {
    if (e.key === 'Escape') defaultsOpen.value = false
    return
  }
  if (previewKey.value) {
    if (e.key === 'Escape') previewKey.value = null
    return
  }
  if (modelHubFor.value) {
    if (e.key === 'Escape') closeModelHub()
    return
  }
  if (searchOpen.value) {
    if (e.key === 'Escape') searchOpen.value = false
    return
  }
  if (templateOpen.value) {
    if (e.key === 'Escape') closeTemplates()
    return
  }
  const mod = e.ctrlKey || e.metaKey
  if (mod && e.key === 's') {
    e.preventDefault()
    void saveNow()
  } else if (mod && e.key.toLowerCase() === 'c' && !isTextEntry(e.target)) {
    e.preventDefault()
    copySelected()
  } else if (mod && e.key.toLowerCase() === 'v' && !isTextEntry(e.target)) {
    e.preventDefault()
    pasteNode()
  } else if (e.key === ' ' && !isTextEntry(e.target) && !runDialog.value) {
    e.preventDefault()
    searchOpen.value = true
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
  runVariables.value = initialVariables(variables.value)
  runDialog.value = true
}

/** 试跑面板：把卡片 / 抽屉上的入口收敛到同一个状态，面板关闭即清空。 */
function openPreview(key: string): void {
  previewKey.value = key
}

const previewNode = computed(() => {
  const node = nodes.value.find((n) => n.id === previewKey.value)
  return node ? { key: node.id, type: node.data.nodeType } : null
})

const llmNodeCount = computed(() => nodes.value.filter((n) => n.data.nodeType === 'llm').length)

/** 保存默认模型：可选同步到现有节点，避免节点一多就漏配。 */
function saveDefaults(payload: { llm: LlmDefaults; applyAll: boolean }): void {
  defaultsOpen.value = false
  defaults.value = { ...defaults.value, llm: payload.llm }
  let applied = 0
  if (payload.applyAll) {
    for (const node of nodes.value) {
      if (node.data.nodeType !== 'llm') continue
      patchNodeConfig(node.id, {
        connectionId: payload.llm.connectionId,
        provider: 'openai-compatible',
        ...(payload.llm.model ? { model: payload.llm.model } : {}),
      })
      applied += 1
    }
  }
  notice.value = applied
    ? `默认模型已保存，并应用到 ${applied} 个 llm 节点`
    : '默认模型已保存，新建 llm 节点会继承它'
}

/**
 * 引擎只认版本快照：触发时不带 versionId 会跑「已发布版本」而不是画布当前草稿。
 * 这正是「向导接完模型、运行仍是 mock 文本」的根因。
 * 这里把草稿冻结成（或复用）一个版本快照并显式传 versionId：
 * 不改已发布版本、不走灰度分流、同内容不重复冻结。
 */
async function draftRunVersionId(checksum: string | undefined): Promise<string | undefined> {
  const detail = await workflowApi.detail(workflowId)
  const published = detail.versions.find((v) => v.id === detail.definition.currentVersionId)
  if (checksum && published?.dslChecksum === checksum) return undefined
  if (checksum) {
    const reused = detail.versions.find((v) => v.dslChecksum === checksum)
    if (reused) return reused.id
  }
  const version = await workflowApi.freeze(workflowId)
  return version.id
}

async function run(): Promise<void> {
  if (runMissing.value.length) {
    error.value = `必填项还没填：${runMissing.value.join('、')}`
    return
  }
  busy.value = true
  error.value = null
  try {
    const variables = runVariables.value
    const dsl = toDsl()
    const saved = await workflowApi.saveDraft(workflowId, dsl)
    if (saveTimer) {
      clearTimeout(saveTimer)
      saveTimer = null
    }
    lastSavedJson = JSON.stringify(dsl)
    saveState.value = 'saved'
    const versionId = await draftRunVersionId(saved?.checksum)
    const result = await executionApi.trigger(workflowId, {
      idempotencyKey: crypto.randomUUID(),
      ...(versionId ? { versionId } : {}),
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
  void loadLlmConnections()
  void loadKnowledgeBases()
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
        title="从常用流程骨架开始"
        @click="openTemplates"
      >
        模板
      </button>
      <button
        class="af-btn af-btn--sm"
        title="新建 llm 节点继承的模型"
        @click="defaultsOpen = true"
      >
        默认模型
      </button>
      <button
        class="af-btn af-btn--sm"
        title="双击画布或按空格唤起"
        @click="searchOpen = true"
      >
        搜索节点
      </button>
      <button
        class="af-btn af-btn--sm"
        title="按依赖层级自动排布"
        @click="tidyLayout"
      >
        整理布局
      </button>
      <button
        class="af-btn af-btn--sm"
        title="Ctrl+C 复制选中节点"
        @click="copySelected"
      >
        复制
      </button>
      <button
        class="af-btn af-btn--sm"
        title="Ctrl+V 粘贴"
        @click="pasteNode"
      >
        粘贴
      </button>
      <button
        class="af-btn af-btn--sm"
        @click="sourceMode = !sourceMode"
      >
        DSL
      </button>
      <router-link :to="{ name: 'versions', params: { id: workflowId } }">
        <button class="af-btn af-btn--sm">版本</button>
      </router-link>
      <router-link :to="{ name: 'triggers', params: { id: workflowId } }">
        <button
          class="af-btn af-btn--sm"
          title="Webhook 与定时触发"
        >触发</button>
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
      v-if="inSubgraph"
      class="af-breadcrumb"
    >
      <template
        v-for="(crumb, i) in crumbs"
        :key="`${crumb.label}-${crumb.level}`"
      >
        <span
          v-if="i"
          class="af-faint"
        >›</span>
        <button
          class="af-btn af-btn--sm"
          :class="{ 'af-btn--primary': crumb.level === path.length }"
          :disabled="crumb.level === path.length"
          :title="crumb.level === path.length ? '当前所在层' : '回到这一层'"
          @click="exitTo(crumb.level)"
        >
          {{ crumb.label }}
        </button>
      </template>
      <span
        class="af-faint"
        style="font-size: 11px"
      >子图：没有 start，出口由上层节点收口</span>
      <div class="af-spacer" />
      <span
        class="mono af-faint"
        style="font-size: 11px"
      >{{ layerTitle }}</span>
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

    <div
      v-if="legacyState?.blocked"
      class="af-panel"
      style="margin: 8px 12px 0; padding: 8px 12px; border-color: var(--af-accent-border); font-size: 12px; line-height: 1.6"
    >
      <span class="af-accent">旧版配置</span> · {{ legacyState.summary }}
      <button
        v-if="llmConnections.length"
        class="af-btn af-btn--sm af-btn--primary"
        style="margin-left: 8px"
        @click="applyConnectionToAllLlm"
      >
        一键切到「{{ llmConnections[0].name }}」
      </button>
      <button
        class="af-btn af-btn--sm"
        style="margin-left: 8px"
        @click="quickConnectDemoModel"
      >
        接入真实模型
      </button>
      <button
        class="af-btn af-btn--sm"
        style="margin-left: 6px"
        @click="goKnowledge"
      >
        导入文档
      </button>
      <div
        class="af-faint"
        style="margin-top: 6px"
      >
        「运行」按画布当前草稿生成版本快照执行，不会偷偷跑旧的已发布版本；「冻结并发布」才会影响正式版本。
      </div>
    </div>

    <div style="flex: 1; display: flex; min-height: 0; position: relative">
      <NodePalette
        :start-taken="hasStart"
        :in-subgraph="inSubgraph"
        @add="addNode"
      />
      <div
        style="flex: 1; position: relative"
        @dblclick="onPaneDblClick"
      >
        <VueFlow
          ref="flowRef"
          v-model:nodes="nodesForFlow"
          v-model:edges="edgesForFlow"
          class="af-flow"
          :delete-key-code="null"
          :selection-key-code="'Shift'"
          :multi-selection-key-code="'Control'"
          :connection-radius="26"
          :fit-view-on-init="true"
          @nodes-initialized="fitViewSoon"
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
            <WorkflowNode
              v-bind="props"
              @update-config="(patch: Record<string, unknown>) => patchNodeConfig(props.id, patch)"
              @expand="openFullConfig(props.id)"
              @pick-model="openModelHub(props.id)"
              @preview="openPreview(props.id)"
            />
          </template>
        </VueFlow>
        <div
          v-if="selectedEdgeId || selectedKey"
          class="af-selection"
        >
          <span class="mono af-dim">{{ selectedEdgeId ? `连线 ${selectedEdgeLabel}` : `节点 ${selectedKey}` }}</span>
          <template v-if="selectedEdgeId && edgeBranchOptions.length">
            <span class="af-faint">branch</span>
            <input
              class="af-input mono af-selection__branch"
              list="af-edge-branches"
              :value="selectedEdgeBranch"
              placeholder="（无）"
              :title="`可选：${edgeBranchOptions.join(' / ')}`"
              @input="setSelectedEdgeBranch(($event.target as HTMLInputElement).value)"
            >
            <datalist id="af-edge-branches">
              <option
                v-for="b in edgeBranchOptions"
                :key="b"
                :value="b"
              />
            </datalist>
          </template>
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
        <NodeSearch
          v-if="searchOpen"
          :in-subgraph="inSubgraph"
          :has-start="hasStart"
          :anchor-id="selectedKey"
          @add="addFromSearch"
          @close="searchOpen = false"
        />
      </div>
      <ConfigDrawer
        :node="selectedNode"
        :issues="issues"
        :model-catalog="modelCatalog"
        :llm-connections="llmConnections"
        :knowledge-bases="knowledgeBases"
        @update="updateNode"
        @close="clearSelection"
        @remove="removeNode"
        @edit-subgraph="enterSubgraph"
        @pick-model="selectedNode && openModelHub(selectedNode.key)"
        @probe-models="probeConnectionModels"
        @open-knowledge="goKnowledge"
        @preview="selectedNode && openPreview(selectedNode.key)"
      />
    </div>

    <VariablesPanel
      :variables="variables"
      @update="variables = $event"
    />

    <ModelHub
      v-if="modelHubFor"
      :current-model="currentModelLabel(modelHubFor)"
      @connected="onModelConnected"
      @builtin="useBuiltinModel"
      @manage="goConnectors"
      @close="closeModelHub"
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
        <div
          v-if="legacyState?.blocked"
          class="af-error-box"
          style="margin-bottom: 10px; line-height: 1.6; font-size: 12px"
        >
          <strong>这条流程跑不起来</strong> · {{ legacyState.summary }}
        </div>
        <label class="af-label">initialVariables</label>
        <p
          class="af-faint"
          style="margin: 0 0 8px; font-size: 12px"
        >
          必填：{{ requiredVariableNames }}；其余为流程内部变量，保持默认即可。
        </p>
        <p
          class="af-faint"
          style="margin: 0 0 8px; font-size: 11px"
        >
          本次执行用画布当前草稿的版本快照，不改变已发布版本；同内容草稿不会重复冻结。
        </p>
        <RunVariablesForm
          v-model="runVariables"
          :variables="variables"
        />
        <p
          v-if="runMissing.length"
          class="af-danger"
          style="margin: 8px 0 0; font-size: 12px"
        >
          还差必填项：{{ runMissing.join('、') }}
        </p>
        <div style="display: flex; gap: 8px; margin-top: 12px; justify-content: flex-end">
          <button
            class="af-btn af-btn--sm"
            @click="runDialog = false"
          >
            取消
          </button>
          <button
            class="af-btn af-btn--sm af-btn--primary"
            :disabled="busy || runMissing.length > 0"
            @click="run"
          >
            触发执行
          </button>
        </div>
      </div>
    </div>

    <WorkflowDefaultsDialog
      v-if="defaultsOpen"
      :current="defaults.llm"
      :catalog="modelCatalog"
      :connections="llmConnections"
      :llm-node-count="llmNodeCount"
      @save="saveDefaults"
      @close="defaultsOpen = false"
    />

    <NodePreviewPanel
      v-if="previewNode"
      :workflow-id="workflowId"
      :node-key="previewNode.key"
      :node-type="previewNode.type"
      :dsl="toDsl()"
      :variables="variables"
      @close="previewKey = null"
    />

    <div
      v-if="templateOpen"
      class="af-modal-backdrop"
      @click.self="closeTemplates"
    >
      <div class="af-panel af-modal">
        <div
          class="af-page-title"
          style="margin-bottom: 8px"
        >
          {{ pendingTemplate ? '确认替换草稿' : '从模板新建' }}
        </div>
        <template v-if="!pendingTemplate">
          <p
            class="af-faint"
            style="margin: 0 0 12px; font-size: 12px; line-height: 1.6"
          >
            模板只替换当前草稿，已发布版本不受影响。模型默认走进程级 OpenAI 兼容端点，
            也可在「连接器」页建连接后由画布一键切换。
          </p>
          <button
            v-for="t in WORKFLOW_TEMPLATES"
            :key="t.id"
            class="af-btn af-btn--sm"
            style="display: block; width: 100%; text-align: left; margin-bottom: 8px"
            @click="pendingTemplate = t"
          >
            <span style="font-weight: 500">{{ t.name }}</span>
            <span
              class="af-faint"
              style="margin-left: 8px; font-size: 11px"
            >{{ t.description }}</span>
          </button>
        </template>
        <template v-else>
          <p
            class="af-faint"
            style="margin: 0; font-size: 12px; line-height: 1.6"
          >
            将用「{{ pendingTemplate?.name }}」替换当前草稿，包含
            {{ pendingTemplate?.dsl.nodes.length ?? 0 }} 个节点、
            {{ pendingTemplate?.dsl.edges.length ?? 0 }} 条连线。
          </p>
          <div style="display: flex; gap: 8px; justify-content: flex-end; margin-top: 12px">
            <button
              class="af-btn af-btn--sm"
              @click="pendingTemplate = null"
            >
              返回
            </button>
            <button
              class="af-btn af-btn--sm af-btn--primary"
              @click="confirmTemplate"
            >
              确认替换
            </button>
          </div>
        </template>
        <div style="margin-top: 12px">
          <button
            class="af-btn af-btn--sm"
            @click="closeTemplates"
          >
            取消
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
.af-breadcrumb {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: var(--af-panel);
  border-bottom: 1px solid var(--af-border);
  flex: none;
}

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

.af-selection__branch {
  width: 120px;
  padding: 2px 6px;
  font-size: 11.5px;
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
