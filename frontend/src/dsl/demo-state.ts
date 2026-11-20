/** 判定导入的旧版 DSL 是否仍含演示节点，用于给出接入提示。 */

import { isBuiltinDemoModel } from './model-presets'
import type { DslNode, WorkflowDsl } from './types'

export interface DemoState {
  /** 走 mock provider / 占位模型名的 llm 节点数。 */
  demoLlm: number
  /** 已挂模型连接或明确指向真实端点的 llm 节点数。 */
  realLlm: number
  /** 仍指向 mock.* 的 tool 节点数。 */
  mockTool: number
  /** 使用真实文档检索的 retrieval 节点数。 */
  retrieval: number
  /** 整图没有任何真实模型/文档检索能力。 */
  demoOnly: boolean
  /** 人话提示；demoOnly=false 时为 null。 */
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
 * 用于模板/示例落位：已有真实连接时不再悄悄留下一堆 mock 节点。
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

export function inspectDemo(dsl: WorkflowDsl): DemoState {
  let demoLlm = 0
  let realLlm = 0
  let mockTool = 0
  let retrieval = 0
  eachNode(dsl.nodes, (n) => {
    const cfg = n.config as Record<string, unknown>
    if (n.type === 'llm') {
      const connected = Boolean(String(cfg.connectionId ?? '').trim())
      const realProvider = cfg.provider === 'openai-compatible' && !isBuiltinDemoModel(cfg)
      if (connected || realProvider) realLlm += 1
      else demoLlm += 1
    } else if (n.type === 'tool') {
      if (String(cfg.connectorId ?? '').startsWith('mock.')) mockTool += 1
    } else if (n.type === 'retrieval') {
      retrieval += 1
    }
  })
  const demoOnly = realLlm === 0 && retrieval === 0 && (demoLlm > 0 || mockTool > 0)
  if (!demoOnly) return { demoLlm, realLlm, mockTool, retrieval, demoOnly, summary: null }
  const parts: string[] = []
  if (demoLlm) parts.push(`${demoLlm} 个演示模型节点`)
  if (mockTool) parts.push(`${mockTool} 个 mock 检索节点`)
  return {
    demoLlm,
    realLlm,
    mockTool,
    retrieval,
    demoOnly,
    summary: `本次运行含 ${parts.join('、')}：输出是确定性示例文本，不会调用真实大模型，也没有检索真实文档。点节点卡片上的「模型」接入服务，或添加「检索」节点并选择知识库。`,
  }
}
