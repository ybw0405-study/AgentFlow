/** 判定 DSL 里是否还残留已移除的离线模型 / 离线连接器，并给出修复提示。 */

import { isLegacyOfflineModel } from './model-presets'
import type { DslNode, WorkflowDsl } from './types'

export interface LegacyState {
  /** 指向已移除离线 provider（或仍在用占位模型名）的 llm 节点数。 */
  offlineLlm: number
  /** 已挂模型连接或走进程端点的 llm 节点数。 */
  realLlm: number
  /** 仍指向 mock.* 的 tool 节点数：这些连接器已不再提供。 */
  legacyTool: number
  /** 使用真实文档检索的 retrieval 节点数。 */
  retrieval: number
  /** 整图没有任何可执行的模型或检索能力，直接运行会在节点上失败。 */
  blocked: boolean
  /** 人话提示；blocked=false 时为 null。 */
  summary: string | null
}

function eachNode(nodes: DslNode[], visit: (n: DslNode) => void): void {
  for (const n of nodes) {
    visit(n)
    const cfg = n.config as Record<string, unknown>
    const body = cfg.body as { nodes?: DslNode[] } | undefined
    if (body?.nodes) eachNode(body.nodes, visit)
    const branches = cfg.branches as Record<string, { nodes?: DslNode[] }> | undefined
    if (branches) {
      for (const branch of Object.values(branches)) {
        if (branch.nodes) eachNode(branch.nodes, visit)
      }
    }
  }
}

/**
 * 把空间里第一条可用模型连接挂到 DSL 的所有 llm 节点（含子图），返回改写数量。
 * 用于模板/示例落位：已有真实连接时不再留下一堆指向已移除 provider 的节点。
 */
export function bindConnectionToLlmNodes(
  dsl: WorkflowDsl,
  connection: { id: string; defaultModel: string },
): number {
  if (!connection.id || !connection.defaultModel) return 0
  let count = 0
  eachNode(dsl.nodes, (n) => {
    if (n.type !== 'llm') return
    n.config = {
      ...(n.config as Record<string, unknown>),
      provider: 'openai-compatible',
      connectionId: connection.id,
      model: connection.defaultModel,
    }
    count += 1
  })
  return count
}

export function inspectLegacyDsl(dsl: WorkflowDsl): LegacyState {
  let offlineLlm = 0
  let realLlm = 0
  let legacyTool = 0
  let retrieval = 0
  eachNode(dsl.nodes, (n) => {
    const cfg = n.config as Record<string, unknown>
    if (n.type === 'llm') {
      const connected = Boolean(String(cfg.connectionId ?? '').trim())
      const realProvider = cfg.provider === 'openai-compatible' && !isLegacyOfflineModel(cfg)
      if (connected || realProvider) realLlm += 1
      else offlineLlm += 1
    } else if (n.type === 'tool') {
      if (String(cfg.connectorId ?? '').startsWith('mock.')) legacyTool += 1
    } else if (n.type === 'retrieval') {
      retrieval += 1
    }
  })
  const blocked = realLlm === 0 && retrieval === 0 && (offlineLlm > 0 || legacyTool > 0)
  if (!blocked) return { offlineLlm, realLlm, legacyTool, retrieval, blocked, summary: null }
  const parts: string[] = []
  if (offlineLlm) parts.push(`${offlineLlm} 个离线模型节点`)
  if (legacyTool) parts.push(`${legacyTool} 个已下线的测试连接器`)
  return {
    offlineLlm,
    realLlm,
    legacyTool,
    retrieval,
    blocked,
    summary: `这条流程含 ${parts.join('、')}：离线 provider 已移除，直接运行会在对应节点失败。点节点卡片上的「模型」接入真实服务，或把工具节点换成「检索」并选择知识库。`,
  }
}
