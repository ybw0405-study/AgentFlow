import type { FlowEdgeLike, FlowNodeLike } from './flow-convert'

/** 按拓扑层级整理节点位置：同一层纵向排开，层与层之间横向留够卡片宽度。
 *  有环或孤立节点时兜底放到最后一层，不会死循环，也不改动 key / config / 边。 */
const LAYER_GAP = 280
const ROW_GAP = 190
const ORIGIN_X = 60
const ORIGIN_Y = 60

export function autoLayout(nodes: FlowNodeLike[], edges: FlowEdgeLike[]): FlowNodeLike[] {
  if (!nodes.length) return nodes

  const ids = nodes.map((n) => n.id)
  const known = new Set(ids)
  const indegree = new Map<string, number>(ids.map((id) => [id, 0]))
  const outgoing = new Map<string, string[]>(ids.map((id) => [id, []]))

  for (const e of edges) {
    const from = String(e.source)
    const to = String(e.target)
    if (!known.has(from) || !known.has(to) || from === to) continue
    indegree.set(to, (indegree.get(to) ?? 0) + 1)
    outgoing.get(from)!.push(to)
  }

  const layer = new Map<string, number>()
  let frontier = ids.filter((id) => (indegree.get(id) ?? 0) === 0)
  if (!frontier.length) frontier = [ids[0]]
  for (const id of frontier) layer.set(id, 0)

  let depth = 0
  const visited = new Set<string>()
  while (frontier.length && depth <= ids.length) {
    const next: string[] = []
    for (const id of frontier) {
      if (visited.has(id)) continue
      visited.add(id)
      for (const to of outgoing.get(id) ?? []) {
        layer.set(to, Math.max(layer.get(to) ?? 0, depth + 1))
        if (!visited.has(to)) next.push(to)
      }
    }
    frontier = next
    depth += 1
  }

  // 既无入边也无出边的孤立节点不挤进主链的起始层，统一放到主链右侧。
  const connected = new Set<string>()
  for (const e of edges) {
    const from = String(e.source)
    const to = String(e.target)
    if (known.has(from) && known.has(to) && from !== to) {
      connected.add(from)
      connected.add(to)
    }
  }
  const maxConnectedLayer = connected.size
    ? Math.max(...Array.from(connected).map((id) => layer.get(id) ?? 0))
    : -1
  const maxLayer = Math.max(...Array.from(layer.values()), 0)
  for (const id of ids) {
    if (!connected.has(id)) layer.set(id, maxConnectedLayer + 1)
    else if (!layer.has(id)) layer.set(id, maxLayer + 1)
  }

  const byLayer = new Map<number, string[]>()
  for (const id of ids) {
    const d = layer.get(id) ?? 0
    if (!byLayer.has(d)) byLayer.set(d, [])
    byLayer.get(d)!.push(id)
  }

  // 超过 MAX_COLS 层就折到下一带：10 个节点的长链若一路向右排，fitView 会把卡片
  // 缩到看不清。折行后宽图变成紧凑网格，且不改动任何持久化坐标（仅用于排布/展示）。
  const MAX_COLS = 4
  const BAND_GAP = 320
  const position = new Map<string, { x: number; y: number }>()
  for (const [d, group] of byLayer) {
    group.sort((a, b) => {
      const na = nodes.find((n) => n.id === a)
      const nb = nodes.find((n) => n.id === b)
      return (na?.position.y ?? 0) - (nb?.position.y ?? 0)
    })
    const col = d % MAX_COLS
    const band = Math.floor(d / MAX_COLS)
    group.forEach((id, i) => {
      position.set(id, {
        x: ORIGIN_X + col * LAYER_GAP,
        y: ORIGIN_Y + band * BAND_GAP + i * ROW_GAP,
      })
    })
  }

  return nodes.map((n) => ({ ...n, position: position.get(n.id) ?? n.position }))
}
