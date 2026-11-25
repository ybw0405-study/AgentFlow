import type { NodeExecutionDto } from '@/types/api'

/** 子图 / 循环轮次的节点 key 形如 `gather#kb#kb_hit`、`polish#0#polish_sign`。
 * 根层图上只画 `gather` / `polish` 一个卡片，子节点状态按「最严重」聚合上去，
 * 避免并行分支里某个失败被整片 SUCCEEDED 盖住。 */
const RANK: Record<string, number> = {
  SKIPPED: 0,
  SUCCEEDED: 1,
  PENDING: 2,
  RUNNING: 3,
  SUSPENDED: 4,
  TIMED_OUT: 5,
  CANCELLED: 6,
  FAILED: 7,
}

export function aggregateStatus(
  nodes: NodeExecutionDto[],
  key: string,
): string | null {
  const exact = nodes.find((n) => n.nodeKey === key)
  if (exact) return exact.status
  const children = nodes.filter((n) => n.nodeKey.startsWith(`${key}#`))
  if (!children.length) return null
  return children.reduce(
    (worst, n) => ((RANK[n.status] ?? 0) > (RANK[worst] ?? 0) ? n.status : worst),
    children[0].status,
  )
}
