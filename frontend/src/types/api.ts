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
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT'
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
  startedAt: string | null
  finishedAt: string | null
}

export interface ExecutionDetail {
  execution: ExecutionDto
  nodes: NodeExecutionDto[]
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
