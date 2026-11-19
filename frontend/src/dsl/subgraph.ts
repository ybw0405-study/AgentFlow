/** 子图（loop.body / parallel.branches.<name>）的纯函数读写。
 *
 * 子图与顶层共用 { nodes, edges } 形状，但定位方式不同：loop 只有一个 body，
 * parallel 按分支名区分。这里只做结构定位，不依赖 Vue / Vue Flow，
 * 也不改入参——视图层拿到新对象后自行替换状态，才能让自动保存的 diff 稳定。
 */

import type { DslEdge, DslNode, WorkflowDsl } from './types'

export interface SubgraphRef {
  /** 持有子图的节点 key（loop / parallel）。 */
  nodeKey: string
  /** 仅 parallel 使用：分支名。loop 的 body 没有分支名。 */
  branch?: string
}

export interface SubgraphGraph {
  nodes: DslNode[]
  edges: DslEdge[]
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}

/** 只接受 { nodes: [], edges: [] } 形状；字段被打坏时返回 null，避免把 {} 当空子图静默覆盖。 */
function normalizeGraph(value: unknown): SubgraphGraph | null {
  const raw = asRecord(value)
  if (!raw || !Array.isArray(raw.nodes)) return null
  const nodes = (raw.nodes as unknown[]).filter(
    (n): n is DslNode => asRecord(n) !== null && typeof (n as DslNode).key === 'string',
  )
  const edges = Array.isArray(raw.edges)
    ? (raw.edges as unknown[]).filter(
        (e): e is DslEdge =>
          asRecord(e) !== null &&
          typeof (e as DslEdge).from === 'string' &&
          typeof (e as DslEdge).to === 'string',
      )
    : []
  return { nodes, edges }
}

/** 用一个子图临时拼出 WorkflowDsl，便于复用只接受 WorkflowDsl 的读函数。 */
function scopedDsl(dsl: WorkflowDsl, graph: SubgraphGraph): WorkflowDsl {
  return {
    dslVersion: dsl.dslVersion,
    variables: dsl.variables,
    defaults: dsl.defaults,
    nodes: graph.nodes,
    edges: graph.edges,
  }
}

/** 读取单个子图；节点不存在、类型不符、分支不存在或结构损坏时返回 null。 */
export function readSubgraph(dsl: WorkflowDsl, ref: SubgraphRef): SubgraphGraph | null {
  const node = dsl.nodes.find((n) => n.key === ref.nodeKey)
  if (!node) return null
  const cfg = asRecord(node.config) ?? {}
  if (node.type === 'loop') {
    // loop 只有 body；带 branch 的 ref 是调用方写错了，按不存在处理
    if (ref.branch !== undefined) return null
    return normalizeGraph(cfg.body)
  }
  if (node.type === 'parallel') {
    if (!ref.branch) return null
    const branches = asRecord(cfg.branches)
    if (!branches) return null
    return normalizeGraph(branches[ref.branch])
  }
  return null
}

/**
 * 写回单个子图。不可变：返回新 DSL，入参不修改。
 * 找不到目标节点 / 分支时原样返回同一个对象，调用方可用引用相等判断是否命中。
 */
export function writeSubgraph(
  dsl: WorkflowDsl,
  ref: SubgraphRef,
  graph: SubgraphGraph,
): WorkflowDsl {
  const index = dsl.nodes.findIndex((n) => n.key === ref.nodeKey)
  if (index < 0) return dsl
  const node = dsl.nodes[index]
  const cfg = asRecord(node.config) ?? {}
  const body: SubgraphGraph = { nodes: graph.nodes, edges: graph.edges }

  let nextConfig: Record<string, unknown>
  if (node.type === 'loop') {
    if (ref.branch !== undefined) return dsl
    nextConfig = { ...cfg, body }
  } else if (node.type === 'parallel') {
    if (!ref.branch) return dsl
    const branches = asRecord(cfg.branches)
    if (!branches || !Object.prototype.hasOwnProperty.call(branches, ref.branch)) return dsl
    nextConfig = { ...cfg, branches: { ...branches, [ref.branch]: body } }
  } else {
    return dsl
  }

  return {
    ...dsl,
    nodes: dsl.nodes.map((n, i) => (i === index ? { ...node, config: nextConfig } : n)),
  }
}

/** 面包屑与报错定位用的可读名：loop_1.body / parallel_1.a。 */
export function subgraphTitle(dsl: WorkflowDsl, ref: SubgraphRef): string {
  const node = dsl.nodes.find((n) => n.key === ref.nodeKey)
  if (ref.branch) return `${ref.nodeKey}.${ref.branch}`
  if (node?.type === 'parallel') return ref.nodeKey
  return `${ref.nodeKey}.body`
}

/** 列出当前层所有可进入的子图；损坏的子图不列出，避免点进去一片空白。 */
export function subgraphRefs(dsl: WorkflowDsl): SubgraphRef[] {
  const refs: SubgraphRef[] = []
  for (const node of dsl.nodes) {
    const cfg = asRecord(node.config) ?? {}
    if (node.type === 'loop') {
      if (normalizeGraph(cfg.body)) refs.push({ nodeKey: node.key })
      continue
    }
    if (node.type !== 'parallel') continue
    const branches = asRecord(cfg.branches)
    for (const name of Object.keys(branches ?? {})) {
      if (normalizeGraph(branches?.[name])) refs.push({ nodeKey: node.key, branch: name })
    }
  }
  return refs
}

/** 路径本身是否成形；能否解析要结合 DSL，见 readSubgraphPath。 */
export function isSubgraphPath(path: readonly SubgraphRef[]): boolean {
  if (!path.length) return false
  return path.every(
    (ref) =>
      typeof ref.nodeKey === 'string' &&
      ref.nodeKey.length > 0 &&
      (ref.branch === undefined || (typeof ref.branch === 'string' && ref.branch.length > 0)),
  )
}

/** 逐层下钻读取；空路径返回顶层图，任一层缺失返回 null。 */
export function readSubgraphPath(
  dsl: WorkflowDsl,
  path: readonly SubgraphRef[],
): SubgraphGraph | null {
  let current = scopedDsl(dsl, { nodes: dsl.nodes, edges: dsl.edges })
  let graph: SubgraphGraph = { nodes: dsl.nodes, edges: dsl.edges }
  for (const ref of path) {
    const next = readSubgraph(current, ref)
    if (!next) return null
    graph = next
    current = scopedDsl(dsl, next)
  }
  return graph
}

/** 逐层写回：先写最内层，再把外层子图整体写回上一层。 */
export function writeSubgraphPath(
  dsl: WorkflowDsl,
  path: readonly SubgraphRef[],
  graph: SubgraphGraph,
): WorkflowDsl {
  if (!path.length) return { ...dsl, nodes: graph.nodes, edges: graph.edges }
  const [head, ...rest] = path
  const parent = readSubgraph(dsl, head)
  if (!parent) return dsl
  const written = writeSubgraphPath(scopedDsl(dsl, parent), rest, graph)
  return writeSubgraph(dsl, head, { nodes: written.nodes, edges: written.edges })
}

/** 子图内的重复 key（只在本层比较：同名 key 出现在不同层是合法的）。 */
export function duplicateSubgraphKeys(graph: SubgraphGraph): string[] {
  const seen = new Set<string>()
  const dup: string[] = []
  for (const node of graph.nodes) {
    if (seen.has(node.key)) dup.push(node.key)
    else seen.add(node.key)
  }
  return dup
}

/**
 * 删除前的子图保护：子图没有 start，必须保留一个 end 收尾。
 * 返回中文原因，null 表示可以删。
 */
export function subgraphRemovalError(graph: SubgraphGraph, key: string): string | null {
  const target = graph.nodes.find((n) => n.key === key)
  if (!target || target.type !== 'end') return null
  if (graph.nodes.filter((n) => n.type === 'end').length > 1) return null
  return '子图至少要保留一个 end 节点：先新增一个 end，再删除这个'
}
