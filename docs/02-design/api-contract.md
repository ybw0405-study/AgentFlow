# API 契约

> 本文件由 `docs/architecture.md` 按主题拆分归档，正文保持原文（含原章节编号）。
> 章节索引与 `§` 交叉引用对照见 [docs/README.md](../README.md)。

## 4. API 契约

### 4.1 对外（gateway `/api/v1`，ProblemDetail 统一错误体含 `traceId`，列表统一 `?cursor=&limit=`）

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/login` `/auth/refresh` `/auth/logout` | JWT + refresh 轮换（复用 DocQA） | 公开 |
| POST/GET/PATCH/DELETE | `/workflows[/{id}]` | 定义 CRUD（DELETE 为软删除） | EDITOR+ |
| PUT | `/workflows/{id}/draft` | 保存草稿（可覆盖，服务端校验 DSL） | EDITOR+ |
| POST | `/workflows/{id}/versions` | 冻结草稿为版本（校验 + checksum） | EDITOR+ |
| POST | `/workflows/{id}/publish` `{versionId}` | 发布为 current | ADMIN+ |
| POST | `/workflows/{id}/rollback` `{versionId}` | current 指向历史版本 | ADMIN+ |
| POST | `/workflows/{id}/executions` | 触发执行（幂等键必需）→ `executionId` + SSE 地址 | EDITOR+（VIEWER 403） |
| GET | `/executions/{id}` | 详情（状态/token/节点列表） | 租户成员 |
| GET | `/executions/{id}/events?afterSeq=` | 事件流（catch-up） | 租户成员 |
| GET | `/executions/{id}/events/stream` | SSE 实时推送 | 租户成员 |
| GET | `/executions/{id}/nodes/{key}/io` | 节点 I/O 原文（node_io） | 租户成员 |
| POST | `/executions/{id}/cancel` | 取消 | EDITOR+ |
| POST | `/executions/{id}/gates/{nodeKey}/resolve` | 审批 approve/reject（P3） | 审批人 |
| GET | `/executions` | 列表（状态/时间/触发人筛选） | 租户成员 |
| CRUD | `/connectors` | 连接器增删改查（列表对成员可见，写操作 ADMIN+） | ADMIN+ |
| PUT | `/connectors/{id}/authorize` | 保存凭据密文（API_KEY/BASIC/OAUTH2 client_secret） | ADMIN+ |
| POST | `/connectors/{id}/oauth/authorize-url` `/oauth/callback` | OAuth2 授权码流程（state 防伪 10 分钟） | ADMIN+ |
| POST | `/connectors/{id}/models/probe` `/connectors/{id}/mcp/tools` `/mcp/resources` `/mcp/prompts` | 模型清单 / MCP 工具·资源·提示清单探测（服务端解密凭据后出网） | 租户成员 |
| GET/POST | `/internal/connectors` `/internal/connectors/{id}/invoke` | 引擎侧目录与调用（X-Internal-Token） | 服务间 |
| GET | `/admin/usage` `/admin/audit` | 复用 DocQA 管理端 | 平台 ADMIN |

横切规则：跨租户访问一律 404 `WF-NOT_FOUND-001`；`policy` 超租户上限时 gateway 钳制到上限并写审计 `WF-POLICY-CLAMPED`（请求被下调）；Kill Switch 开启时执行类请求 503 `AGT-UPSTREAM-003`。

### 4.2 内部契约（gateway `/engine/**` → engine-api，全部要求 `X-Internal-Token` + `traceparent`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/executions` | 派发执行。体：`{executionId, versionSnapshot, tenantId, spaceId, authorizedConnectorIds[], initialVariables(已脱敏), policy(已钳制), traceId}` → 202 |
| POST | `/internal/executions/{id}/cancel` | 取消 |
| POST | `/internal/executions/{id}/gates/{key}/resolve` | 审批回调（P3，另走 MQ 兜底） |
| GET | `/internal/events?afterSeq=&limit=` | 全局事件尾读（Projector 用） |
| GET | `/internal/executions/{id}/events?afterSeq=` | 单执行事件（SSE catch-up 用） |
| GET | `/internal/executions/{id}/nodes/{key}/io` | 节点 I/O（gateway 已做权限） |
| GET | `/internal/health` `/internal/metrics` | 健康/指标 |

Java 侧四个服务统一暴露 `GET /health`（不走 `/api/v1` 前缀，免认证）：网关探 Redis，workflow / connector 探 MySQL + Redis，audit 探 MySQL。200 为 `UP`，503 为 `DEGRADED`；生产守护下不回依赖明细。探针实现留在各服务内，`common` 只提供 `HealthProbe` 抽象——保持 common 不依赖 `java.sql`（§13.1 架构门禁）。

---

