/** DSL TypeScript 类型（与 engine-api app/dsl/models.py 一一对应）。 */

export type NodeType =
  | 'start'
  | 'end'
  | 'llm'
  | 'retrieval'
  | 'tool'
  | 'condition'
  | 'loop'
  | 'parallel'
  | 'transform'
  | 'subworkflow'
  | 'human_gate'

export interface VariableSpec {
  type: 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array'
  required?: boolean
  default?: unknown
  pii?: boolean
  description?: string
}

export interface RetryPolicy {
  maxAttempts?: number
  backoffMs?: number
  backoffMultiplier?: number
}

export interface DslDefaults {
  timeoutMs?: number
  maxParallelism?: number
  retry?: RetryPolicy
}

export interface LlmConfig {
  provider?: 'mock' | 'openai-compatible'
  /** 模型连接 id：端点与密钥由服务端托管，DSL 里只留这个引用。 */
  connectionId?: string
  model: string
  promptTemplate: string
  systemPrompt?: string
  temperature?: number
  maxTokens?: number
  outputMode?: 'text' | 'json'
  outputSchema?: Record<string, unknown>
}

export interface ToolConfig {
  connectorId: string
  operation: string
  paramsMapping?: Record<string, string>
  idempotent?: boolean
}

export interface EndConfig {
  resultMapping: Record<string, string>
}

export type NodeConfig = LlmConfig | ToolConfig | EndConfig | Record<string, unknown>

export interface DslNode {
  key: string
  type: NodeType
  name?: string
  timeoutMs?: number
  retry?: RetryPolicy
  position?: { x: number; y: number }
  config: Record<string, unknown>
}

export interface DslEdge {
  from: string
  to: string
  branch?: string
}

export interface WorkflowDsl {
  dslVersion: '1.0'
  variables: Record<string, VariableSpec>
  defaults?: DslDefaults
  nodes: DslNode[]
  edges: DslEdge[]
}

export interface NodeTypeMeta {
  type: NodeType
  label: string
  description: string
  /** 固定分支（边的 branch 字段）；condition 的分支随 cases 变化，不在此列。 */
  branches?: readonly string[]
}

/** 画布可添加的节点类型。retrieval 引擎未实现执行器，不放进面板，避免建出必失败的节点。 */
export const NODE_TYPE_METAS: NodeTypeMeta[] = [
  { type: 'start', label: 'Start', description: '入口：校验并合并初始变量' },
  { type: 'llm', label: 'LLM', description: '模型调用（连接器或进程级端点）' },
  { type: 'tool', label: 'Tool', description: '连接器工具调用（内置 mock.*）' },
  {
    type: 'condition',
    label: 'Condition',
    description: '按表达式选分支：每个 case 一条出边',
  },
  { type: 'transform', label: 'Transform', description: '把表达式结果写回 vars.*' },
  { type: 'subworkflow', label: 'Subworkflow', description: '调用另一个已发布工作流' },
  { type: 'loop', label: 'Loop', description: '遍历数组执行子图（体在 DSL 里维护）' },
  { type: 'parallel', label: 'Parallel', description: '多分支并发执行后汇合' },
  {
    type: 'human_gate',
    label: 'HumanGate',
    description: '人工审批：approved / rejected 两条出边',
    branches: ['approved', 'rejected'],
  },
  { type: 'end', label: 'End', description: '结果映射输出' },
]

export const BRANCH_NODE_TYPES: readonly NodeType[] = ['condition', 'human_gate']

export function isBranchNode(type: NodeType | string): boolean {
  return (BRANCH_NODE_TYPES as readonly string[]).includes(type)
}

/**
 * 该节点允许的出边 branch 取值。
 * condition 的分支名来自它自己的 cases，human_gate 固定 approved/rejected。
 */
export function branchOptions(node: {
  type: NodeType | string
  config: Record<string, unknown>
}): string[] {
  if (node.type === 'human_gate') return ['approved', 'rejected']
  if (node.type === 'condition') {
    const cases = (node.config.cases as { name?: string }[] | undefined) ?? []
    const names = cases.map((c) => String(c?.name ?? '')).filter(Boolean)
    const elseBranch = String(node.config.elseBranch ?? 'default')
    return [...names, elseBranch]
  }
  return []
}
