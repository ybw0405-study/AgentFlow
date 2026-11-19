/** 画布图操作：纯函数，不依赖 DOM 与响应式，便于单测。
 *
 * 选中态（selected）只属于画布交互，flowToDsl 只读 source/target/label，
 * 因此这里附加的字段不会污染持久化的 DSL。
 */

import { edgeId } from './flow-convert'
import type { FlowEdgeLike, FlowNodeLike } from './flow-convert'

export type CanvasEdge = FlowEdgeLike & { selected?: boolean }

/** 建边；同一对 source→target 已存在时返回 null（重复连线的 id 会互相覆盖）。 */
export function addEdge(
  edges: readonly CanvasEdge[],
  source: string,
  target: string,
): CanvasEdge[] | null {
  const id = edgeId({ from: source, to: target })
  if (edges.some((e) => e.id === id)) return null
  return [...edges, { id, source, target }]
}

export function removeEdge(edges: readonly CanvasEdge[], id: string): CanvasEdge[] {
  return edges.filter((e) => e.id !== id)
}

/**
 * 设置连线的分支标签。
 *
 * 边的 id 由 from/to/branch 共同决定（见 edgeId），改分支必须同步改 id，否则选中态与
 * 后续删除会指向一条不存在的边。分支取值与已有边重复时返回 null。
 */
export function setEdgeBranch(
  edges: readonly CanvasEdge[],
  id: string,
  branch: string,
): CanvasEdge[] | null {
  const target = edges.find((e) => e.id === id)
  if (!target) return null
  // 同一源节点的分支名必须唯一：两张边同叫 yes 会让该分支同时点亮两个下游
  const clash = edges.some(
    (e) =>
      e.id !== id &&
      e.source === target.source &&
      (typeof e.label === 'string' ? e.label : '') === branch,
  )
  if (clash) return null
  const nextId = edgeId({ from: target.source, to: target.target, branch: branch || undefined })
  return edges.map((e) =>
    e.id === id
      ? { ...e, id: nextId, label: branch || undefined, selected: e.selected }
      : e,
  )
}

/** 删节点时级联删掉它的进出边，避免留下悬空 edge。 */
export function removeNode(
  nodes: readonly FlowNodeLike[],
  edges: readonly CanvasEdge[],
  key: string,
): { nodes: FlowNodeLike[]; edges: CanvasEdge[] } {
  return {
    nodes: nodes.filter((n) => n.id !== key),
    edges: edges.filter((e) => e.source !== key && e.target !== key),
  }
}

/** 单选：只保留 id 这条边为选中，其余一律取消。 */
export function markSelectedEdge(
  edges: readonly CanvasEdge[],
  id: string | null,
): CanvasEdge[] {
  return edges.map((e) => ({ ...e, selected: e.id === id }))
}
