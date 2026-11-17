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

export const P0_NODE_TYPES: readonly NodeType[] = ['start', 'end', 'llm', 'tool']

export interface NodeTypeMeta {
  type: NodeType
  label: string
  description: string
}

export const NODE_TYPE_METAS: NodeTypeMeta[] = [
  { type: 'start', label: 'Start', description: '入口：校验并合并初始变量' },
  { type: 'llm', label: 'LLM', description: '模型调用（P0 默认 mock provider）' },
  { type: 'tool', label: 'Tool', description: '连接器工具调用（P0 内置 mock.*）' },
  { type: 'end', label: 'End', description: '结果映射输出' },
]
