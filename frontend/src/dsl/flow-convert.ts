/** Vue Flow ↔ DSL 双向转换。position 随 DSL 持久化（引擎忽略该字段）。
 *
 * 注意：为避开 Vue Flow 深层泛型在 vue-tsc 下的实例化爆炸，本模块只用
 * 结构化的 FlowNodeLike/FlowEdgeLike；视图层在边界处一次性断言。 */

import type { DslEdge, DslNode, NodeType, RetryPolicy, WorkflowDsl } from './types'

export const AF_NODE_TYPE = 'af'

export interface FlowNodeData {
  nodeType: NodeType
  label: string
  config: Record<string, unknown>
  /** 引擎 NodeBase 的顶层字段，与 config 平级：不能写进 config（extra=forbid）。 */
  timeoutMs?: number
  retry?: RetryPolicy
}

export interface FlowNodeLike {
  id: string
  type?: string
  position: { x: number; y: number }
  data: FlowNodeData
}

export interface FlowEdgeLike {
  id: string
  source: string
  target: string
  label?: string
}

export interface FlowGraph {
  nodes: FlowNodeLike[]
  edges: FlowEdgeLike[]
}

export function dslToFlow(dsl: WorkflowDsl): FlowGraph {
  const nodes: FlowNodeLike[] = dsl.nodes.map((n, i) => ({
    id: n.key,
    type: AF_NODE_TYPE,
    position: n.position ?? { x: 80 + i * 220, y: 120 + (i % 3) * 90 },
    data: {
      nodeType: n.type,
      label: n.name ?? n.key,
      config: n.config,
      ...(n.timeoutMs !== undefined ? { timeoutMs: n.timeoutMs } : {}),
      ...(n.retry !== undefined ? { retry: n.retry } : {}),
    },
  }))
  const edges: FlowEdgeLike[] = dsl.edges.map((e) => ({
    id: edgeId(e),
    source: e.from,
    target: e.to,
    label: e.branch,
  }))
  return { nodes, edges }
}

export function flowToDsl(
  graph: FlowGraph,
  variables: WorkflowDsl['variables'],
  defaults?: WorkflowDsl['defaults'],
): WorkflowDsl {
  const nodes: DslNode[] = graph.nodes.map((n) => ({
    key: n.id,
    type: n.data.nodeType,
    name: n.data.label === n.id ? undefined : n.data.label,
    ...(n.data.timeoutMs !== undefined ? { timeoutMs: n.data.timeoutMs } : {}),
    ...(n.data.retry !== undefined ? { retry: n.data.retry } : {}),
    position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    config: n.data.config ?? {},
  }))
  const edges: DslEdge[] = graph.edges.map((e) => ({
    from: String(e.source),
    to: String(e.target),
    branch: typeof e.label === 'string' && e.label ? e.label : undefined,
  }))
  return {
    dslVersion: '1.0',
    variables,
    defaults,
    nodes,
    edges,
  }
}

export function edgeId(e: { from: string; to: string; branch?: string }): string {
  return `${e.from}->${e.to}${e.branch ? `#${e.branch}` : ''}`
}

/** 新建节点的初始配置。
 *
 * 默认值一律指向真实执行路径：有模型连接就挂 connectionId，否则走进程级
 * OpenAI 兼容端点。未注入 AF_LLM_* 时执行会明确报 LLM-NOT_CONFIGURED-001，
 * 由画布提示补齐配置，而不是静默产出确定性示例文本。
 */
export function newNodeConfig(
  type: DslNode['type'],
  llmModel?: string,
  llmConnection?: { id: string; defaultModel: string } | null,
  knowledgeBaseId?: string,
): Record<string, unknown> {
  switch (type) {
    case 'llm':
      return {
        provider: 'openai-compatible',
        ...(llmConnection ? { connectionId: llmConnection.id } : {}),
        model: llmConnection?.defaultModel || llmModel || 'deepseek-chat',
        promptTemplate: '{{ vars.question }}',
        maxTokens: 1024,
      }
    case 'retrieval':
      return {
        knowledgeBaseIds: knowledgeBaseId ? [knowledgeBaseId] : [],
        queryTemplate: '{{ vars.question }}',
        topK: 5,
        minScore: 0,
      }
    case 'tool':
      // 不预填任何 mock 连接器：真实连接由连接器页创建，留空由校验提示补齐。
      return { connectorId: '', operation: '', paramsMapping: {} }
    case 'condition':
      // when 用字面量 True：空工作流里没有变量可引用，先给一个能发布的起点
      return { cases: [{ name: 'yes', when: 'True' }], elseBranch: 'default' }
    case 'transform':
      return { mappings: [{ target: 'vars.summary', template: '' }] }
    case 'human_gate':
      return {
        approvers: { type: 'ROLE', role: 'ADMIN' },
        promptTemplate: '请确认是否继续',
        timeoutMs: 86_400_000,
        onTimeout: { action: 'REJECT' },
      }
    case 'subworkflow':
      return {
        definitionId: '',
        versionPolicy: 'LATEST_PUBLISHED',
        inputMapping: {},
        outputMapping: {},
      }
    case 'loop':
      // body 是一张独立子图：子图内成员用 itemVar/indexVar，无需额外声明变量
      return {
        itemsExpression: '[]',
        itemVar: 'item',
        indexVar: 'index',
        maxIterations: 100,
        concurrency: 1,
        body: {
          nodes: [
            { key: 'body_end', type: 'end', config: { resultMapping: { value: '{{ vars.item }}' } } },
          ],
          edges: [],
        },
      }
    case 'parallel':
      return {
        branches: {
          a: { nodes: [{ key: 'a_end', type: 'end', config: { resultMapping: { value: 'a' } } }], edges: [] },
          b: { nodes: [{ key: 'b_end', type: 'end', config: { resultMapping: { value: 'b' } } }], edges: [] },
        },
        join: 'all',
        maxConcurrency: 4,
      }
    case 'end':
      return { resultMapping: { answer: '{{ vars.question }}' } }
    default:
      return {}
  }
}
