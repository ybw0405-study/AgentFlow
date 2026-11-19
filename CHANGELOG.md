# 变更日志

记录对外可见的行为变化。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循语义化版本；`0.x` 期间不保证向后兼容。

## [0.2.0] - 2026-11-19

把引擎早已支持、但画布够不着的节点能力放出来，同时修掉子图校验的两个作用域问题。

### 新增

- 画布节点面板由 4 类扩到 9 类：`condition` / `transform` / `subworkflow` / `loop` /
  `parallel` / `human_gate` 都能直接拖入，并各有配置表单（条件 case 列表、映射行编辑、
  审批人规则与超时动作、子工作流版本策略等）。`retrieval` 仍不提供——引擎没有它的执行器。
- 分支连线可在画布上编辑：选中从 `condition` / `human_gate` 出发的连线，浮条出现 `branch`
  输入框，候选来自该节点的 case 名（审批节点固定 `approved` / `rejected`）；同一节点分支
  名重复会被拒绝，避免一条分支同时点亮两个下游。
- `loop.body` / `parallel.branches` 提供 JSON 编辑区，解析失败时保留输入但不写回草稿。
- 新增 `e2e` 流水线：起真基础设施与五个服务，跑 `e2e/` 32 个全栈验收用例，失败时上传日志。
  其中新增一组画布节点用例（`condition` 分支路由、`loop` 迭代 + `collect`、`parallel` 汇合）
  与运维探针用例（`/health` 无凭据可访问且如实反映依赖）。

### 修复

- 子图作用域：`loop.body` 里写 `vars.item` 会被判成「未声明变量」，带数据引用的 loop
  因此**根本发布不了**；`parallel` 分支内引用子图内节点也同样被判未知。校验改为按子图
  作用域递归：外层变量 + 迭代变量可见，节点引用可以是子图内上游或外层上游。
- 子图内的 `branch` 边此前完全不校验，写错分支名要等运行期才炸；现在与顶层同规则。
- `transform.mappings[].target` 与 `loop.collect.into` 是变量**定义**，此前被当成引用，
  导致「先算一个临时变量」必须先在工作流变量里声明一遍；现在它们对下游直接可见
  （兄弟分支仍不可见，与运行期一致）。
- 分支工作流会丢掉最终产出：收口取的是「定义里的第一个 `end`」，当两条分支各有自己的
  `end` 时，被 SKIPPED 的那个会顶上来，`ExecutionCompleted.result` 恒为空对象，
  执行详情页只能看到空结果。改为只收真正 `SUCCEEDED` 的 `end`（子图内同样修正）。
- 用量占比 `tokenUtilization` 会超过 1（累计用量超过限额后线性增长），进度条量程溢出；
  改为夹在 `[0,1]`，真实超额看 `tokensUsed` / `effectiveTokenLimit` 两个原始字段。
- `start-dev.ps1` 的就绪探测每次都误报「未在 120s 内就绪」：Windows PowerShell 5.1 的
  `Invoke-WebRequest` 默认走 IE DOM 解析，非交互模式下一律抛错，探测永远不成立。补
  `-UseBasicParsing`，并打印实际就绪耗时（实测约 17s）。
- 依赖断连时 gate 扫描每 15s 打一屏 traceback，降为单行 WARNING，堆栈落到 DEBUG。
- 本地与 CI 都没开 `AF_MQ_ENABLED`，`workflow-service` 走 `NoopAuditPublisher`：
  灰度、发布、执行触发这些审计事件一条都没落库，而 e2e 靠历史遗留数据"通过"了。
  现在 `start-dev` 与 `e2e` 流水线默认开启 MQ，并等 RocketMQ broker healthy
  才拉起服务（compose 里 broker 此前没有健康检查）。
- 审计查询的 `payload_redacted AS payloadRedacted` 与 `AuditRow.payloadJson` 名不对号，
  接口返回的 `payloadJson` 恒为 `null`，前端审计详情看不到载荷；别名改为与 DTO 一致。
- 仓库根目录补 `pytest.ini`：根下 `pytest` 即全栈 e2e 套件，同时消掉 pytest-asyncio
  的 loop scope 弃用告警（此前只有 `engine-api/` 下有配置）。

## [0.1.1] - 2026-11-19

### 修复

- 评测收口会漏掉「轮询先于执行结束」的用例：`advance` 把结果标记为 `RUNNING` 后，
  下一轮只扫 `PENDING` 就再也扫不到它，`eval_run` 永久停在 `RUNNING`（全量 e2e 实测复现）。
  改为重扫 `PENDING` 与 `RUNNING`，历史卡住的记录在服务重启后自动收敛。
- 本地启动脚本只放宽了触发限流、没放宽登录限流，与 README 描述不符：全量 e2e 一轮就有十几次登录，
  连跑两轮即触发 10 次/60s 的 `429`。`start-dev.ps1` / `start-dev.sh` 现同时设置
  `AF_RATE_LIMIT_LOGIN_MAX=300`（产品默认值不变）。

## [0.1.0] - 2026-11-19

首个可运行版本：P0 编排主链路完整，P1/P2/P3 能力按阶段落地。

### 新增

**控制面（Java 17 / Spring Boot 3.5，Maven 多模块）**

- `agent-gateway`：JWT 认证与 refresh 轮换、租户上下文注入、固定窗口限流（Redis 原子计数）、
  熔断、KillSwitch，`/engine/**` 为引擎唯一入口。
- `workflow-service`：工作流 CRUD / 草稿 / 版本冻结（不可变 + checksum）/ 发布 / 回滚；
  执行索引与事件投影（游标轮询）、SSE 实时推送与断线追平；审批中心收件箱与裁决；
  评测集与 A/B 对比。
- `audit-service`：只追加审计（DB 触发器 + 最小授权账号 + RocketMQ 消费），审计查询接口。
- `connector-service`：连接器目录、AES-256-GCM 凭据加密与主密钥轮换、OAuth2 换取、
  出网沙箱（域名白名单 + 解析期私网拦截）、OpenAI 兼容模型连接的模型清单拉取。
- `common`：统一响应体与 ProblemDetail（含 `traceId`）、游标分页、ORG → TEAM → SPACE
  三级租户继承。

**数据面（Python 3.11+ / FastAPI）**

- DSL 校验与编译、拓扑分层调度、并行上限、重试 / 超时 / 取消。
- 事件溯源：PostgreSQL `event_log` 只追加，`execution_spec` 自足，Checkpoint + 启动/周期
  扫描实现崩溃恢复与确定性重放。
- 预算预占与硬停止（发出 Command 前钳制 maxTokens）、Redis 租约、`human_gate` 挂起与
  超时自动裁决、`gate_resolve_outbox` 重投。
- LLM provider：mock / OpenAI 兼容 / 经连接器转发；执行详情记录实际下发的模型名。
- Prometheus 指标、结构化日志、`traceparent` 透传。

**前端（Vue 3 + Vue Flow）**

- 画布编排：start / llm / tool / end 四类节点，DSL 双向转换、自动保存、内联校验、
  节点与连线删除（删节点级联删边）。
- 执行详情（SSE 实时节点状态 + 事件追平）、审批中心、连接器管理、评测与 A/B、管理后台。

**工程与交付**

- 一键启动 / 停止脚本（Windows PowerShell 与 WSL2 bash），自动完成基础设施编排、迁移、
  种子与四服务拉起。
- 三端门禁 `scripts/verify-all.*`：`mvn verify`（测试 + Checkstyle + ArchUnit）、
  `ruff` + `pytest`、`tsc` + `eslint` + `vitest` + `build`。
- GitHub Actions：`java-ci` / `python-ci` / `frontend-ci` / `contract-drift`，
  Action 固定到 commit SHA、最小 `contents: read` 权限、按 ref 并发取消。
- 架构文档（1226 行，含 DSL Schema 与错误码目录）、引擎 OpenAPI 基线、E2E 验收套件、
  SECURITY、MIT LICENSE、PR 模板、EditorConfig 与 LF 行尾约定。

### 变更

- 接入真实模型的主路径改为「先在连接器页建模型连接」，端点与密钥只存在服务端，
  画布只引用连接 id；环境变量单端点路径保留为本地调试手段。
- 画布不再产出 mock 节点：模型下拉来自服务端清单，也允许手填清单外的模型名。

### 修复

- SSE 实时推送失效，补齐事件追平与链路测试。
- Windows 下运行中的 jar 独占导致 `spring-boot:repackage` 失败：`stop-dev` 在构建前真正停服
  并清理残留 `*.jar.original`。
- 网关限流计数改为原子操作，避免并发下的超额放行。
- 引擎截止时间锚定 `ExecutionCreated` 时刻，崩溃恢复与审批续跑不再重置超时预算。
- 跨租户访问统一 404（不泄露资源存在性）；生产守护改为多信号判定，未满足即 fail-fast。
- 连接器出网响应体改为流式限长读取，避免大响应打满内存。

### 已知限制

见 [README「已知限制」](README.md#已知限制)，主要包括：审计依赖 MQ 开启、沙箱未做 DNS 钉扎、
Java 侧 OpenAPI 未入库、画布面板只暴露四类节点（其余走 DSL 编辑）。
