/** API 类型（与 Java 侧 ApiResponse/ProblemDetail/记录契约对应）。 */

export interface ApiEnvelope<T> {
  code: string
  msg: string
  data: T
  traceId: string
}

export interface ProblemDetail {
  type?: string
  title?: string
  status: number
  detail?: string
  code?: string
  traceId?: string
  errors?: { field: string; message: string }[]
}

export interface PageResult<T> {
  items: T[]
  nextCursor: string | null
}

export interface LoginData {
  accessToken: string
  tokenType: string
  expiresIn: number
}

export interface Membership {
  tenantId: string
  name: string
  type: string
  role: string
}

export interface MeData {
  userId: string
  email: string
  displayName: string | null
  memberships: Membership[]
}

export interface DemoAccount {
  email: string
  password: string
  label: string
  role: string
}

export interface BootstrapData {
  demoMode: boolean
  demoAccounts: DemoAccount[]
}

export interface WorkflowDefinitionDto {
  seq: number
  id: string
  name: string
  description: string | null
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
  currentVersionId: string | null
  draftDsl: string | null
  draftChecksum: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowVersionDto {
  id: string
  definitionId: string
  versionNo: number
  dslJson: string
  dslChecksum: string
  publishedAt: string | null
  publishedBy: string | null
  createdAt: string
}

export interface WorkflowDetail {
  definition: WorkflowDefinitionDto
  versions: WorkflowVersionDto[]
}

export interface ExecutionDto {
  seq: number
  id: string
  definitionId: string
  versionId: string
  status: 'PENDING' | 'RUNNING' | 'SUSPENDED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT'
  triggeredBy: string
  traceId: string
  tokenBudget: number | null
  maxSteps: number | null
  tokenUsed: number
  stepsUsed: number
  errorCode: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}

export interface NodeExecutionDto {
  seq: number
  nodeKey: string
  displayOrder: number
  status: string
  attempt: number
  outputDigest: string | null
  tokenUsed: number
  latencyMs: number | null
  retryCount: number
  errorCode: string | null
  /** 失败原因（引擎 NodeFailed 的 message，最多 500 字）。 */
  errorMessage?: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface ExecutionDetail {
  execution: ExecutionDto
  nodes: NodeExecutionDto[]
  /** 终态执行的最终产出（引擎事件流里的 end 结果）；未终结或引擎不可达时为 null。 */
  result?: unknown
}

export interface EngineEvent {
  seq: number
  executionId: string
  eventType: string
  nodeKey: string | null
  payload: Record<string, unknown>
  aggregateVersion: number
  createdAt: string
}

export interface EventsPage {
  events: EngineEvent[]
  lastSeq: number
}

export interface TriggerData {
  executionId: string
  status: string
  idempotentReplay: boolean
  eventsUrl: string
  streamUrl: string
}

export interface NodeIo {
  in: { payload: Record<string, unknown>; contentHash: string } | null
  out: { payload: Record<string, unknown>; contentHash: string } | null
}

export type ConnectorAuthType = 'NONE' | 'API_KEY' | 'BASIC' | 'OAUTH2'

export interface ConnectorDto {
  id: string
  tenantId: string
  name: string
  type: string
  authType: ConnectorAuthType
  config: Record<string, unknown>
  enabled: boolean
  hasCredential: boolean
  inherited: boolean
}

export interface GateTaskDto {
  id: string
  executionId: string
  nodeKey: string
  prompt: string
  approvers: string[]
  status: string
  timeoutAt?: string
  defaultBranch?: string
  createdAt?: string
  executionStatus?: string
  definitionId?: string
  triggeredBy?: string
}

export interface NotificationDto {
  id: string
  type: string
  payloadJson?: string
  readAt?: string
  createdAt?: string
}

export interface EvalDatasetDto {
  id: string
  name: string
  description?: string
  createdAt?: string
}

export interface EvalCaseDto {
  id: string
  datasetId: string
  name: string
  inputJson: string
  expectedJson?: string
  assertionJson: string
  enabled: boolean
}

export interface EvalRunDto {
  id: string
  datasetId: string
  definitionId: string
  versionId: string
  status: string
  total: number
  passed: number
  failed: number
  variant?: string
  experimentId?: string
  errorMessage?: string
  createdAt?: string
}

export interface EvalResultDto {
  id: string
  caseId: string
  caseName: string
  executionId?: string
  status: string
  passed?: boolean
  actual?: unknown
  latencyMs?: number
  tokenUsed?: number
  errorMessage?: string
}

export interface EvalRunDetail {
  run: EvalRunDto
  results: EvalResultDto[]
}

export interface ExperimentDto {
  id: string
  name: string
  status: string
  definitionId: string
  datasetId: string
  versionA: string
  versionB: string
  runA?: string
  runB?: string
  summaryJson?: string
  createdAt?: string
}

/** 引擎侧模型清单。configured=false 表示进程没有配置真实端点，前端应退回手填。 */
export interface LlmModelsDto {
  configured: boolean
  defaultModel: string
  models: string[]
  error: string | null
  /** 引擎附带的能力标注；拿不到能力字段的模型只有 id。 */
  items?: ModelFactsDto[]
}

/** 模型能力标注：只用于界面提示，不参与任何执行决策。 */
export interface ModelFactsDto {
  id: string
  tags: string[]
  contextWindow: number | null
  maxOutputTokens: number | null
  /** upstream=上游 /models 给的字段；inferred=按模型名推断。 */
  source: 'upstream' | 'inferred'
}

/**
 * 探测回来的单个模型：服务端只做白名单搬运（contextWindow / inputModalities /
 * supportedParameters / reasoningEffort），标签规则留在前端一处，避免多语言各抄一份。
 */
export interface ProbeModelItem {
  id: string
  contextWindow?: number | null
  maxOutputTokens?: number | null
  inputModalities?: string[] | null
  supportedParameters?: string[] | null
  reasoningEffort?: boolean | null
}

/** 模型连接探测结果：只回模型 id 与能力字段，端点与密钥不出服务端。 */
export interface ModelProbeDto {
  ok: boolean
  models: string[]
  items: ProbeModelItem[]
  errorCode: string | null
}

/** 模型连接里 config 的已知字段，仅在读取时用来收窄类型。 */
export interface LlmConnectionConfig {
  baseUrl?: string
  allowedHosts?: string[]
  models?: string[]
  /** 上次探测到的能力字段；只是展示用缓存，重新探测会整份覆盖。 */
  modelItems?: ProbeModelItem[]
  defaultModel?: string
  auth?: { headerName?: string; valuePrefix?: string }
  operations?: Record<string, { method?: string; path?: string }>
}

/** 文档知识库（V009）。chunkCount 是导入时切好的片段数，不是示例数据。 */
export interface KnowledgeBaseDto {
  id: string
  name: string
  description: string | null
  documentCount: number
  chunkCount: number
  createdAt: string | null
}

export interface KnowledgeDocumentDto {
  id: string
  kbId: string
  title: string
  source: string
  charCount: number
  chunkCount: number
  createdAt: string | null
}

export interface KnowledgeHitDto {
  chunkId: string
  documentId: string
  documentTitle: string
  kbId: string
  ordinal: number
  content: string
  score: number
}

export interface KnowledgeSearchResult {
  results: KnowledgeHitDto[]
  query: string
  matchedChunks: number
  scannedChunks: number
}

/** 单节点试跑结果：上游真实跑过一遍，这里是目标节点的输出与整条链路的状态。 */
export interface NodePreviewTraceItem {
  key: string
  status: string
  tokenUsed: number
  latencyMs: number | null
  errorCode: string | null
}

export interface NodePreviewResult {
  executionId: string
  nodeKey: string
  status: string
  nodeStatus: string
  output: Record<string, unknown> | null
  input: Record<string, unknown> | null
  errorCode: string | null
  errorMessage: string | null
  tokenUsed: number
  latencyMs: number | null
  totalTokens: number
  result: Record<string, unknown> | null
  trace: NodePreviewTraceItem[]
}

export type TriggerType = 'WEBHOOK' | 'SCHEDULE'

/** 工作流外部触发器；token 即入口凭据，secret 用于校验 X-AF-Signature。 */
export interface TriggerDto {
  id: string
  type: TriggerType
  token: string
  secret: string
  cron: string | null
  timezone: string
  enabled: boolean
  variables: Record<string, unknown>
  versionId: string | null
  /** 真实跑起来的瞬间（ISO），前端按浏览器时区渲染。 */
  lastFiredAt: string | null
  /**
   * 这一跑代表 cron 的哪一档，触发器时区的墙钟串（`yyyy-MM-dd HH:mm`）。
   * 未触发过时后端不下发这个键（`non_null` 序列化），所以是可选的。
   */
  lastSlotAt?: string | null
  /** 下一档，与 `nextFireTimes[0]` 相同；同样按触发器时区格式化。 */
  nextFireAt?: string | null
  nextFireTimes?: string[]
  createdAt: string | null
}

/** cron 预览：保存前确认「这条 cron 接下来会在这几个时间点跑」。 */
export interface CronPreviewDto {
  cron: string
  timezone: string
  times: string[]
}

/** 用量汇总：正式执行与试跑分开统计，试跑的 token 也是真金白银。 */
export interface UsageTotals {
  count: number
  tokens: number
}

export interface UsageBudget {
  period: string
  callsUsed: number
  tokensUsed: number
  callLimit: number
  tokenLimit: number
  /** 已夹到 [0,1]，供进度条使用；真实超额看原始数字。 */
  callUtilization: number
  tokenUtilization: number
}

export interface UsageByDefinition {
  definitionId: string
  name: string
  executions: number
  executionTokens: number
  previews: number
  previewTokens: number
  tokens: number
}

export interface UsageDaily {
  date: string
  executionTokens: number
  previewTokens: number
  tokens: number
}

export interface UsageRecentPreview {
  id: string
  definitionId: string
  definitionName: string | null
  nodeKey: string
  actorId: string | null
  status: string
  model: string | null
  tokenUsed: number
  latencyMs: number | null
  createdAt: string
}

export interface UsageSummary {
  from: string
  to: string
  days: number
  executions: UsageTotals
  previews: UsageTotals
  totalTokens: number
  /** 试跑占区间总消耗的比例，[0,1]。 */
  previewShare: number
  budget: UsageBudget
  byDefinition: UsageByDefinition[]
  daily: UsageDaily[]
  recentPreviews: UsageRecentPreview[]
}
