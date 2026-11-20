import { del, get, patch, post, put } from './http'
import type {
  BootstrapData,
  ConnectorDto,
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
  detail: (id: string) => get<WorkflowDetail>(`/workflows/${id}`),
  patch: (id: string, body: { name?: string; description?: string; status?: string }) =>
    patch<{ ok: boolean }>(`/workflows/${id}`, body),
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
  detail: (id: string) => get<ExecutionDetail>(`/executions/${id}`),
  list: (params: { status?: string; definitionId?: string; cursor?: string; limit?: number }) =>
    get<PageResult<ExecutionDto>>('/executions', params),
  events: (id: string, afterSeq = 0, limit = 500) =>
    get<EventsPage>(`/executions/${id}/events`, { afterSeq, limit }),
  nodeIo: (id: string, nodeKey: string) => get<NodeIo>(`/executions/${id}/nodes/${nodeKey}/io`),
  cancel: (id: string) => post<{ ok: boolean; status: string }>(`/executions/${id}/cancel`),
}

export interface AdminBudgetRow {
  tenantId: string
  period: string
  callsUsed: number
  tokensUsed: number
  limitCalls: number
  limitTokens: number
}

export interface AdminUsageRow {
  tenantId: string
  status: string
  cnt: number
  tokens: number
}

export interface AdminUsage {
  period: string
  budgetLedger: AdminBudgetRow[]
  executions: AdminUsageRow[]
}

export interface AdminAuditRow {
  seq: number
  id: string
  tenantId: string
  traceId: string
  eventType: string
  createdAt: string
}

export const adminApi = {
  usage: (period?: string) =>
    get<AdminUsage>('/admin/usage', period ? { period } : undefined),
  audit: (params: { traceId?: string; eventType?: string; cursor?: string; limit?: number }) =>
    get<PageResult<AdminAuditRow>>('/admin/audit', params),
}

export const llmApi = {
  models: () => get<LlmModelsDto>('/llm/models'),
}

/** 外部触发：webhook（公开入口）与定时（cron）。 */
export const triggerApi = {
  list: (definitionId: string) => get<TriggerDto[]>(`/workflows/${definitionId}/triggers`),
  create: (
    definitionId: string,
    body: { type: TriggerType; cron?: string; variables?: Record<string, unknown> },
  ) => post<TriggerDto>(`/workflows/${definitionId}/triggers`, body),
  patch: (
    triggerId: string,
    body: { enabled?: boolean; cron?: string; variables?: Record<string, unknown> },
  ) => patch<TriggerDto>(`/triggers/${triggerId}`, body),
  rotate: (triggerId: string) => post<TriggerDto>(`/triggers/${triggerId}/rotate`),
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
