# 变更日志

记录对外可见的行为变化。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循语义化版本；`0.x` 期间不保证向后兼容。

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
