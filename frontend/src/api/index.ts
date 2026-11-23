import { del, get, patch, post, put } from './http'
import type {
  BootstrapData,
  ConnectorDto,
  CronPreviewDto,
  EvalCaseDto,
  EvalDatasetDto,
  EvalRunDetail,
  EvalRunDto,
  EventsPage,
  ExperimentDto,
  GateTaskDto,
  NotificationDto,
  ExecutionDetail,
  ExecutionDto,
  LoginData,
  MeData,
  LlmModelsDto,
  McpPromptsDto,
  McpResourcesDto,
  McpToolsDto,
  MisfirePolicy,
  ModelProbeDto,
  KnowledgeBaseDto,
  KnowledgeDocumentDto,
  KnowledgeSearchResult,
  NodeIo,
  NodePreviewResult,
  PageResult,
  TriggerData,
  TriggerDto,
  TriggerType,
  UsageSummary,
  WorkflowDetail,
  WorkflowDefinitionDto,
  WorkflowVersionDto,
} from '@/types/api'
import type { WorkflowDsl } from '@/dsl/types'

export const authApi = {
  login: (email: string, password: string) =>
    post<LoginData>('/auth/login', { email, password }),
  refresh: () => post<LoginData>('/auth/refresh'),
  logout: () => post<void>('/auth/logout'),
  me: () => get<MeData>('/auth/me'),
  bootstrap: () => get<BootstrapData>('/auth/bootstrap'),
}

export const workflowApi = {
  create: (name: string, description?: string) =>
    post<{ id: string }>('/workflows', { name, description }),
  list: (params: { status?: string; keyword?: string; cursor?: string; limit?: number }) =>
    get<PageResult<WorkflowDefinitionDto>>('/workflows', params),
  detail: (id: string, signal?: AbortSignal) =>
    get<WorkflowDetail>(`/workflows/${id}`, undefined, signal),
  patch: (
    id: string,
    body: { name?: string; description?: string; status?: string; maxConcurrent?: number },
  ) => patch<{ ok: boolean }>(`/workflows/${id}`, body),
  remove: (id: string) => del<{ ok: boolean }>(`/workflows/${id}`),
  saveDraft: (id: string, dsl: WorkflowDsl) =>
    put<{ ok: boolean; checksum: string }>(`/workflows/${id}/draft`, dsl),
  freeze: (id: string) => post<WorkflowVersionDto>(`/workflows/${id}/versions`),
  publish: (id: string, versionId: string) =>
    post<{ ok: boolean }>(`/workflows/${id}/publish`, { versionId }),
  rollback: (id: string, versionId: string) =>
    post<{ ok: boolean }>(`/workflows/${id}/rollback`, { versionId }),
  /**
   * 试跑到某个节点。dsl 传画布当前草稿：试跑要跑的就是眼前这份配置，
   * 不能悄悄换成已保存的旧草稿。
   */
  previewNode: (
    id: string,
    key: string,
    body: { dsl: WorkflowDsl; variables: Record<string, unknown> },
  ) => post<NodePreviewResult>(`/workflows/${id}/nodes/${key}/preview`, body),
}

export const connectorApi = {
  list: () => get<ConnectorDto[]>('/connectors'),
  create: (body: {
    name: string
    type?: string
    authType: ConnectorDto['authType']
    config: Record<string, unknown>
  }) => post<ConnectorDto>('/connectors', body),
  update: (
    id: string,
    body: {
      name?: string
      authType?: ConnectorDto['authType']
      config?: Record<string, unknown>
      enabled?: boolean
    },
  ) => patch<ConnectorDto>(`/connectors/${id}`, body),
  remove: (id: string) => del<void>(`/connectors/${id}`),
  authorize: (id: string, secret: string) =>
    put<void>(`/connectors/${id}/authorize`, { secret }),
  /** 拉取模型连接指向端点的模型清单（服务端解密凭据后出网，只回 id）。 */
  probeModels: (id: string) => post<ModelProbeDto>(`/connectors/${id}/models/probe`, {}),
  /** 拉取 MCP 连接暴露的工具清单（服务端解密凭据后出网，只回 name/description/inputSchema）。 */
  probeMcpTools: (id: string) => post<McpToolsDto>(`/connectors/${id}/mcp/tools`, {}),
  /** 拉取 MCP 资源清单（uri/name/description/mimeType）。 */
  probeMcpResources: (id: string) => post<McpResourcesDto>(`/connectors/${id}/mcp/resources`, {}),
  /** 拉取 MCP 提示清单（name/description/arguments 摘要）。 */
  probeMcpPrompts: (id: string) => post<McpPromptsDto>(`/connectors/${id}/mcp/prompts`, {}),
}

export const gateApi = {
  inbox: () => get<GateTaskDto[]>('/gates'),
  byExecution: (executionId: string) => get<GateTaskDto[]>(`/executions/${executionId}/gates`),
  resolve: (executionId: string, nodeKey: string, decision: 'APPROVED' | 'REJECTED', comment?: string) =>
    post<{ status: string; branch?: string; deferred?: boolean }>(
      `/executions/${executionId}/gates/${nodeKey}/resolve`,
      { decision, comment },
    ),
}

export const notificationApi = {
  list: (unreadOnly = false) =>
    get<{ items: NotificationDto[]; unread: number }>('/notifications', { unreadOnly }),
  read: (id: string) => post<{ ok: boolean }>(`/notifications/${id}/read`),
  readAll: () => post<{ ok: boolean }>('/notifications/read-all'),
}

export const evalApi = {
  datasets: () => get<EvalDatasetDto[]>('/evals/datasets'),
  createDataset: (body: { name: string; description?: string }) =>
    post<{ dataset: EvalDatasetDto; cases: EvalCaseDto[]; runs: EvalRunDto[] }>(
      '/evals/datasets',
      body,
    ),
  dataset: (id: string) =>
    get<{ dataset: EvalDatasetDto; cases: EvalCaseDto[]; runs: EvalRunDto[] }>(
      `/evals/datasets/${id}`,
    ),
  removeDataset: (id: string) => del<{ ok: boolean }>(`/evals/datasets/${id}`),
  addCase: (
    datasetId: string,
    body: {
      name: string
      input: Record<string, unknown>
      expected?: unknown
      assertion: Record<string, unknown>
    },
  ) => post<{ cases: EvalCaseDto[] }>(`/evals/datasets/${datasetId}/cases`, body),
  removeCase: (id: string) => del<{ ok: boolean }>(`/evals/cases/${id}`),
  startRun: (datasetId: string, body: { definitionId: string; versionId?: string }) =>
    post<EvalRunDetail>(`/evals/datasets/${datasetId}/runs`, body),
  runs: (datasetId: string, limit = 20) =>
    get<EvalRunDto[]>(`/evals/datasets/${datasetId}/runs`, { limit }),
  run: (id: string) => get<EvalRunDetail>(`/evals/runs/${id}`),
  experiments: (definitionId?: string) =>
    get<ExperimentDto[]>('/evals/experiments', { definitionId, limit: 20 }),
  createExperiment: (body: {
    definitionId: string
    datasetId: string
    name: string
    versionA: string
    versionB: string
  }) => post<{ experiment: ExperimentDto; runA: EvalRunDto; runB: EvalRunDto }>(
    '/evals/experiments',
    body,
  ),
  experiment: (id: string) =>
    get<{
      experiment: ExperimentDto
      runA: EvalRunDto | null
      runB: EvalRunDto | null
      statsA: Record<string, number> | null
      statsB: Record<string, number> | null
    }>(`/evals/experiments/${id}`),
}

export const executionApi = {
  trigger: (
    definitionId: string,
    body: {
      versionId?: string
      idempotencyKey: string
      variables?: Record<string, unknown>
      policy?: { tokenBudget?: number; maxSteps?: number; deadlineMs?: number }
    },
  ) => post<TriggerData>(`/workflows/${definitionId}/executions`, body),
  // 下列可选末位 signal 供执行详情页卸载时取消在途 REST；省略时与原有调用完全一致。
  detail: (id: string, signal?: AbortSignal) =>
    get<ExecutionDetail>(`/executions/${id}`, undefined, signal),
  list: (params: { status?: string; definitionId?: string; cursor?: string; limit?: number }) =>
    get<PageResult<ExecutionDto>>('/executions', params),
  events: (id: string, afterSeq = 0, limit = 500, signal?: AbortSignal) =>
    get<EventsPage>(`/executions/${id}/events`, { afterSeq, limit }, signal),
  nodeIo: (id: string, nodeKey: string, signal?: AbortSignal) =>
    get<NodeIo>(`/executions/${id}/nodes/${nodeKey}/io`, undefined, signal),
  cancel: (id: string, signal?: AbortSignal) =>
    post<{ ok: boolean; status: string }>(`/executions/${id}/cancel`, undefined, signal),
}

/** 管理端一行的租户账面：账本用量 + 生效限额 + 限额来源。 */
export interface AdminTenantUsage {
  tenantId: string
  name: string
  type: string
  parentId: string | null
  callsUsed: number
  tokensUsed: number
  /** 试跑消耗是账本的一部分，单独列出来才知道额度被调试吃掉了多少。 */
  previewCalls?: number
  previewTokens?: number
  /** 本账期执行数（含失败），失败只数 FAILED。 */
  executions?: number
  executionsFailed?: number
  /** 租户自己账本行上写的限额；没有账本行时为 undefined。 */
  ownCallLimit?: number | null
  ownTokenLimit?: number | null
  /** 生效限额：沿 parent 链取最小。undefined 代表无上限（后端不下发空值）。 */
  effectiveCallLimit?: number | null
  effectiveTokenLimit?: number | null
  /** 卡住这一维额度的租户：等于自身 id 表示「本租户」，否则是某个祖先。 */
  limitedByCall?: string | null
  limitedByCallName?: string | null
  limitedByToken?: string | null
  limitedByTokenName?: string | null
  inherited?: boolean
  /** unbounded 无上限 / ok / near（≥80%）/ over（已到顶）。 */
  state: string
  callUtilization: number
  tokenUtilization: number
}

export interface AdminTotals {
  tenants: number
  calls: number
  tokens: number
  previewCalls: number
  previewTokens: number
  /** 试跑 token 占平台合计的比例，[0,1]。 */
  previewShare: number
  executions: number
  executionsFailed: number
  /** 已超限 / 接近上限的租户数。 */
  over: number
  near: number
}

/** 跨租户按模型分解：同一模型在两个来源里合并，tenants 是并集而不是相加。 */
export interface AdminModelUsage {
  model?: string | null
  executionTokens: number
  previewTokens: number
  tokens: number
  tenants: number
}

/** 交叉表的一列（一个模型）与它的合计；`model=null` 是「未标注」。 */
export interface AdminCrossColumn {
  model?: string | null
  tokens: number
  calls: number
}

/** 交叉表的一个格子；缺失的组合是 0，不是错位。 */
export interface AdminCrossCell {
  tokens: number
  calls: number
}

export interface AdminCrossRow {
  tenantId: string
  name: string
  /** 与 `cross.models` 一一对应，长度相同。 */
  cells: AdminCrossCell[]
  tokens: number
  calls: number
}

/**
 * 「租户 × 模型」二维。
 * 行列合计由服务端给出：前端自己透视会多出一个真源，页面上两处加不起来时没人知道信谁。
 */
export interface AdminCross {
  models: AdminCrossColumn[]
  rows: AdminCrossRow[]
  grand: AdminCrossCell
}

export interface AdminUsage {
  period: string
  from: string
  tenants: AdminTenantUsage[]
  totals: AdminTotals
  byModel: AdminModelUsage[]
  cross: AdminCross
}

export interface AdminAuditRow {
  seq: number
  id: string
  tenantId: string
  traceId: string
  eventType: string
  createdAt: string
}

/**
 * 一条「卡住不动的执行」。
 *
 * `neverStarted` 区分两种卡法：`true` 是派发就丢了（PENDING，引擎从没收到），
 * `false` 是跑过但没落终态（已经超过自己的 deadline）。两者的处置不同，
 * 界面上也不该混成同一句话。
 */
export interface AdminStaleExecution {
  executionId: string
  tenantId: string
  definitionId: string
  status: string
  createdAt: string | null
  deadlineAt: string | null
  overdueSeconds: number
  neverStarted: boolean
}

export interface AdminSweepReport {
  scanned: number
  reconciled: number
  reaped: number
  alive: number
  failed: number
}

export const adminApi = {
  usage: (period?: string) =>
    get<AdminUsage>('/admin/usage', period ? { period } : undefined),
  audit: (params: { traceId?: string; eventType?: string; cursor?: string; limit?: number }) =>
    get<PageResult<AdminAuditRow>>('/admin/audit', params),
  /** 执行看护的候选：与看护实际会处理的同一套口径（只读）。 */
  staleExecutions: (limit?: number) =>
    get<AdminStaleExecution[]>('/admin/executions/stale', limit ? { limit } : undefined),
  /** 立刻跑一轮看护（幂等，可重复点）。 */
  sweepExecutions: () => post<AdminSweepReport>('/admin/executions/sweep'),
}

export const llmApi = {
  models: () => get<LlmModelsDto>('/llm/models'),
}

/** 外部触发：webhook（公开入口）与定时（cron）。 */
export const triggerApi = {
  list: (definitionId: string) => get<TriggerDto[]>(`/workflows/${definitionId}/triggers`),
  create: (
    definitionId: string,
    body: {
      type: TriggerType
      cron?: string
      misfirePolicy?: MisfirePolicy
      variables?: Record<string, unknown>
    },
  ) => post<TriggerDto>(`/workflows/${definitionId}/triggers`, body),
  patch: (
    triggerId: string,
    body: {
      enabled?: boolean
      cron?: string
      misfirePolicy?: MisfirePolicy
      variables?: Record<string, unknown>
    },
  ) => patch<TriggerDto>(`/triggers/${triggerId}`, body),
  rotate: (triggerId: string) => post<TriggerDto>(`/triggers/${triggerId}/rotate`),
  previewCron: (
    definitionId: string,
    body: { cron: string; timezone?: string; count?: number },
  ) => post<CronPreviewDto>(`/workflows/${definitionId}/triggers/cron-preview`, body),
  remove: (triggerId: string) => del<{ ok: boolean }>(`/triggers/${triggerId}`),
}

/** 用量：只读汇总，按当前空间统计；试跑与正式执行分列。 */
export const usageApi = {
  summary: (days = 30) => get<UsageSummary>(`/usage/summary?days=${days}`),
}

/** 文档知识库：纯文本导入（前端读文件后按 JSON 提交，服务端不开放 multipart）。 */
export const knowledgeApi = {
  bases: () => get<KnowledgeBaseDto[]>('/knowledge/bases'),
  createBase: (name: string, description?: string) =>
    post<KnowledgeBaseDto>('/knowledge/bases', { name, description }),
  removeBase: (id: string) => del<{ ok: boolean }>(`/knowledge/bases/${id}`),
  documents: (id: string) => get<KnowledgeDocumentDto[]>(`/knowledge/bases/${id}/documents`),
  addDocument: (id: string, body: { title?: string; content: string; source?: string }) =>
    post<KnowledgeDocumentDto>(`/knowledge/bases/${id}/documents`, body),
  removeDocument: (id: string) => del<{ ok: boolean }>(`/knowledge/documents/${id}`),
  search: (body: { knowledgeBaseIds: string[]; query: string; topK?: number; minScore?: number }) =>
    post<KnowledgeSearchResult>('/knowledge/search', body),
}
