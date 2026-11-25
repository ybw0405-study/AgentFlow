import type { FlowEdgeLike, FlowNodeLike } from './flow-convert'

/** 落线前的即时校验。返回 null 表示可连；返回文案时画布直接提示，不再等发布期 422。
 * 规则与发布期校验保持一致，只做前置提示，不新增约束。 */
export interface ConnectionLike {
  source: string
  target: string
  sourceHandle?: string | null
  targetHandle?: string | null
}

export function connectionProblem(
  nodes: FlowNodeLike[],
  edges: FlowEdgeLike[],
  conn: ConnectionLike,
  inSubgraph = false,
): string | null {
  const source = nodes.find((n) => n.id === conn.source)
  const target = nodes.find((n) => n.id === conn.target)
  if (!source || !target) return '连线的两端必须都在当前画布里'
  if (conn.source === conn.target) return '节点不能连到自己'
  if (source.data.nodeType === 'end') return 'end 是收口节点，不能再往外连出边'
  if (target.data.nodeType === 'start') {
    return inSubgraph
      ? '子图没有 start，入口由上层节点决定'
      : 'start 是入口，不能作为连线终点'
  }
  if (edges.some((e) => String(e.source) === conn.source && String(e.target) === conn.target)) {
    return '这条连线已经存在'
  }
  return null
}
