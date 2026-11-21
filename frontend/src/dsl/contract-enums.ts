/**
 * DSL / 跨端枚举的**单一事实源**（CT-1）。
 *
 * 此前这些取值散落在：node-card.ts、types.ts 的联合类型、validate.ts、
 * ConfigDrawer.vue 的手写 <option>、TriggersView.vue 的策略数组、api.ts。
 * 修 P1-1 时只钉住了 2 个常量，其余副本继续静默漂移，重现「选完保存 422」。
 *
 * 纪律：
 *  1. 任何下拉 / 校验 / 类型都从这里 import，模板一律 v-for，不再抄字面量；
 *  2. tests/contract-enums.spec.ts 把每个数组与权威源对账：
 *     engine-api/app/dsl/models.py（Pydantic Literal）、db/mysql/migration
 *     V006 的 status ENUM、workflow-service MisfirePolicy.java 的枚举常量；
 *  3. 新增枚举必须同时补 contract-enums.ts 与一条对账用例。
 */

// ---- 引擎 Pydantic Literal（models.py）----
export const OUTPUT_MODES = ['text', 'json'] as const
export const LLM_PROVIDERS = ['openai-compatible'] as const
export const VARIABLE_TYPES = ['string', 'number', 'integer', 'boolean', 'object', 'array'] as const
export const NODE_TYPES = [
  'start', 'end', 'llm', 'retrieval', 'tool', 'condition', 'loop',
  'parallel', 'transform', 'subworkflow', 'human_gate',
] as const
export const SUBWORKFLOW_VERSION_POLICIES = ['LATEST_PUBLISHED', 'FIXED'] as const
export const PARALLEL_JOIN_POLICIES = ['all', 'first_success'] as const
export const APPROVER_TYPES = ['ROLE', 'USERS', 'EXPRESSION'] as const
export const APPROVER_ROLES = ['OWNER', 'ADMIN', 'EDITOR', 'VIEWER'] as const
export const TIMEOUT_ACTIONS = ['REJECT', 'DEFAULT_BRANCH'] as const

// ---- 执行状态（MySQL workflow_execution.status，V006 最终 ENUM）----
export const EXECUTION_STATUSES = [
  'PENDING', 'RUNNING', 'SUSPENDED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT',
] as const

// ---- misfire 策略（workflow-service MisfirePolicy.java）----
export const MISFIRE_POLICY_VALUES = ['CATCH_UP_LATEST', 'CATCH_UP_ALL', 'SKIP'] as const

// ---- 派生类型：联合类型不再手写，避免与取值数组漂移 ----
export type OutputMode = (typeof OUTPUT_MODES)[number]
export type LlmProvider = (typeof LLM_PROVIDERS)[number]
export type VariableType = (typeof VARIABLE_TYPES)[number]
export type NodeType = (typeof NODE_TYPES)[number]
export type SubworkflowVersionPolicy = (typeof SUBWORKFLOW_VERSION_POLICIES)[number]
export type ParallelJoinPolicy = (typeof PARALLEL_JOIN_POLICIES)[number]
export type ApproverType = (typeof APPROVER_TYPES)[number]
export type ApproverRole = (typeof APPROVER_ROLES)[number]
export type TimeoutAction = (typeof TIMEOUT_ACTIONS)[number]
export type ExecutionStatus = (typeof EXECUTION_STATUSES)[number]
export type MisfirePolicyValue = (typeof MISFIRE_POLICY_VALUES)[number]

// ---- 展示文案：只描述含义，取值仍由上面的数组提供（v-for 里不出现第二份字面量）----
export const OUTPUT_MODE_LABELS: Record<OutputMode, string> = {
  text: '纯文本',
  json: '结构化 JSON',
}
export const LLM_PROVIDER_LABELS: Record<LlmProvider, string> = {
  'openai-compatible': 'openai-compatible（真实模型）',
}
export const SUBWORKFLOW_VERSION_POLICY_LABELS: Record<SubworkflowVersionPolicy, string> = {
  LATEST_PUBLISHED: '跟随最新发布',
  FIXED: '锁定版本',
}
export const PARALLEL_JOIN_LABELS: Record<ParallelJoinPolicy, string> = {
  all: '全部分支成功',
  first_success: '首个成功即返回',
}
export const APPROVER_TYPE_LABELS: Record<ApproverType, string> = {
  ROLE: '按角色',
  USERS: '按用户',
  EXPRESSION: '按表达式',
}
export const TIMEOUT_ACTION_LABELS: Record<TimeoutAction, string> = {
  REJECT: '自动驳回',
  DEFAULT_BRANCH: '走默认分支',
}
