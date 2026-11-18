/** Vue Flow ↔ DSL 双向转换。position 随 DSL 持久化（引擎忽略该字段）。
 *
 * 注意：为避开 Vue Flow 深层泛型在 vue-tsc 下的实例化爆炸，本模块只用
 * 结构化的 FlowNodeLike/FlowEdgeLike；视图层在边界处一次性断言。 */

import type { DslEdge, DslNode, NodeType, WorkflowDsl } from './types'

export const AF_NODE_TYPE = 'af'

export interface FlowNodeData {
  nodeType: NodeType
  label: string
  config: Record<string, unknown>
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
    data: { nodeType: n.type, label: n.name ?? n.key, config: n.config },
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
 * llm 默认接真实 provider：模型名取引擎给出的默认值，拿不到就留空由校验提示，
 * 不再写死 mock-* 占位名——那会让节点看起来能跑、实际走的是服务端默认模型。
 */
export function newNodeConfig(
  type: DslNode['type'],
  llmModel?: string,
  llmConnection?: { id: string; defaultModel: string } | null,
): Record<string, unknown> {
  switch (type) {
    case 'llm':
      // 已有模型连接就直接挂上：新建的节点开箱可用，不必再去翻连接列表
      return {
        provider: 'openai-compatible',
        ...(llmConnection ? { connectionId: llmConnection.id } : {}),
        model: llmModel || llmConnection?.defaultModel || '',
        promptTemplate: '{{ vars.question }}',
        maxTokens: 1024,
      }
    case 'tool':
      return { connectorId: 'mock.search', operation: 'search', paramsMapping: {} }
    case 'end':
      return { resultMapping: { answer: '{{ vars.question }}' } }
    default:
      return {}
  }
}
