# AgentFlow · 可视化 Agent 工作流编排平台

企业级可视化 Agent 工作流编排：画布拖拽编排 DAG（LLM / 工具 / 检索 / 条件 / 循环 / 人工审批），事件溯源执行引擎（崩溃可恢复、副作用恰好一次），全链路治理（租户隔离、预算硬停止、PII 双层脱敏、审计只追加）。

## 架构

```mermaid
flowchart LR
    FE[Vue3 + Vue Flow 前端] --> GW[agent-gateway :8080<br/>认证·限流·KillSwitch·熔断]
    GW --> WF[workflow-service :8084<br/>工作流/版本/执行索引/投影/SSE]
    GW --> AUD[audit-service :8086<br/>MQ 消费 + 审计查询]
    GW --> CONN[connector-service :8085<br/>连接器/凭据加密/出网沙箱]
    WF -->|/engine/** 唯一入口| GW
    GW -->|X-Internal-Token| ENG[engine-api :8000<br/>DSL 编译·拓扑调度·事件溯源·重放]
    ENG -->|/internal/connectors 工具调用| GW
    ENG --> PG[(PostgreSQL event_log<br/>append-only)]
    ENG --> RD[(Redis 租约/预算/事件频道)]
    WF --> MY[(MySQL 业务库)]
    AUD --> MY
    WF -->|af-audit-event| MQ[[RocketMQ]]
    MQ --> AUD
    RD -->|pub/sub 事件流| WF
```

- **事件即唯一真相**：MySQL 的 execution/node_execution 只是 PG 事件流的查询投影（Projector 轮询 + 游标）。
- **恰好一次**：Command 与结果成对落事件；`callId = executionId/nodeKey/attempt` 确定性幂等；`aggregate_version` 乐观并发兜底；Redis 租约做冲突降级。
- **预算硬停止**：发出 Command 前 Redis 预占并把 maxTokens 钳到剩余额度，`tokenUsed ≤ tokenBudget` 恒成立。

## 功能矩阵

| 能力 | 状态 |
|---|---|
| 工作流 CRUD / 草稿 / 版本冻结（不可变+checksum）/ 发布 / 回滚 | ✅ P0 |
| 画布编辑器（Vue Flow，4 节点，DSL 双向，自动保存，校验内联） | ✅ P0 |
| 执行引擎（拓扑调度、并行上限、重试、超时、取消、checkpoint） | ✅ P0 |
| 事件溯源 + 崩溃恢复（execution_spec 自足 + 启动/周期扫描） | ✅ P0 提前实现 |
| SSE 实时节点状态 + 事件追平 | ✅ P0 |
| JWT + refresh 轮换 + 租户隔离（跨租户 404）+ RBAC | ✅ P0 |
| 健康检查（各服务 `/health`，依赖不可用返回 503） | ✅ P0 |
| 预算钳制 / 硬停止、PII 双层脱敏（pii-v2 双端同规则） | ✅ P0 |
| 审计只追加（触发器 + 最小授权 + MQ） | ✅ P0 |
| mock LLM / mock 连接器（引擎侧离线演示与 CI，画布默认不产出） | ✅ P0 |

画布交互：单击选中节点或连线，`Delete` 删除（删节点会级联删掉它的进出连线），`Esc` 或点空白处取消选中，底部浮条会显示当前选中项并提供删除按钮。`start` 全局唯一：已有 start 时节点面板里的 start 置灰，避免建出第二个起点。
| condition / loop / parallel / transform / subworkflow 节点 | ✅ P1 |
| 灰度发布、故障注入测试套件 | ✅ P1 |
| 三级租户继承（ORG → TEAM → SPACE，OWNER 降级继承） | ✅ P2 |
| connector-service：CRUD / AES-256-GCM 凭据 / OAuth2 / 出网沙箱 | ✅ P2 |
| 成本中心：budget_ledger 投影累加、有效限额继承、预算对账 | ✅ P2 |
| human_gate 人工审批：挂起/恢复、超时自动裁决、裁决幂等（V006） | ✅ P3 |
| 审批中心：待办收件箱、通过/驳回、审批人通知、outbox 重投 | ✅ P3 |
| 评测集：数据集/用例、真实执行跑分、四种断言、轮询收口 | ✅ P3 |
| A/B 对比：同数据集双版本、通过率→耗时→token 结论口径 | ✅ P3 |

## 快速开始（Windows）

前置：Docker Desktop、JDK 17、Maven 3.9+、Node ≥22、Python ≥3.11。

```powershell
# 1) 安装 Python 依赖（一次）
cd engine-api; python -m venv .venv; .venv/Scripts/pip install -e ".[dev]"; cd ..
# 2) 安装前端依赖（一次）
cd frontend; npm install; cd ..
# 3) 一键启动（基础设施 + 迁移 + 种子 + 四服务 + 前端）
./scripts/start-dev.ps1
```

打开 <http://127.0.0.1:5174> ，账号（密码统一 `agentflow123`）：

> 前端固定监听 `127.0.0.1:5174`（IPv4），避开常被本机其他项目（如 DocQA）占用的 5173；端口与协议在 `frontend/vite.config.ts` 中以 `strictPort` 固定，若 5174 被占用会直接报错而非静默顺延。

| 账号 | 角色 | 空间 |
|---|---|---|
| admin@agentflow.dev | OWNER + 平台 ADMIN | Alpha / Beta |
| editor@agentflow.dev | EDITOR | Space Alpha |
| viewer@agentflow.dev | VIEWER（触发执行会 403） | Space Alpha |
| beta@agentflow.dev | EDITOR | Space Beta（访问 Alpha 资源一律 404） |

WSL2：把第 3 步换成 `bash scripts/start-dev.sh`（先 `./scripts/stop-dev.ps1` 停掉 Windows 侧）。

## 接入真实模型（OpenAI 兼容）

推荐做法是在「连接器」页建一条**模型连接**：端点与密钥存在服务端（AES-256-GCM 密文），画布只引用连接 id，密钥不进 DSL、不落画布，换密钥也不必重启引擎。

1. 「连接器」→ 类型选「模型接入（OpenAI 兼容）」，填名称、Base URL（如 `https://api.deepseek.com/v1`）、API Key。允许域名留空会取 Base URL 的域名。
2. 「模型」一栏可以直接填自己用的模型名（逗号分隔，如 `deepseek-chat, deepseek-reasoner`）；留空则创建时自动拉一次 `/models`。手填与拉取结果会取并集，上游不支持 `/models` 时靠手填照样能用。
3. 画布选中 llm 节点，「模型来源」选这条连接，「model」下拉列出该连接的模型，也允许直接填清单外的名字；新建 llm 节点会自动挂上第一条模型连接。执行详情里 `output.model` 是**实际下发**的模型名。

也可以不用连接、走进程级单端点（适合本地快速试）：在**启动脚本之前**设置环境变量（脚本不会覆盖它们，子进程会继承）。

   ```powershell
   $env:AF_LLM_BASE_URL = 'https://api.deepseek.com/v1'   # 或 Qwen/Ollama/OpenAI 等兼容端点
   $env:AF_LLM_API_KEY  = '<你的密钥>'
   $env:AF_LLM_MODEL    = 'deepseek-chat'
   .\scripts\start-dev.ps1
   ```

此时「模型来源」留「进程默认」，model 下拉来自 `GET /api/v1/llm/models`。两种来源都允许手填模型名；端点不通或上游不支持 `/models` 时退回手填并说明原因，不会把配置卡死。

约束与排错：

- 经连接调用时，端点与密钥**只在 connector-service 解密**并注入请求头；引擎与画布都只有连接 id。走进程级配置时同理，端点与密钥只来自环境变量，DSL 无法指定 URL。两条路径都不把密钥写进日志、错误体、事件载荷。
- 上游 `401/403` → `LLM-AUTH-001`（永久）；`429/5xx/超时` → 瞬时错误，由引擎 `RetryPolicy` 重试；`400/404/422` → `LLM-REQUEST-001`（多为模型名不对）。未配置环境变量 → `LLM-NOT_CONFIGURED-001`。
- 若上游不支持 `response_format`，设 `AF_LLM_JSON_RESPONSE_FORMAT=false`。
- 真实模型是**外部非确定性副作用**，崩溃恢复重放可能重复计费（at-least-once）；内置 mock 才是确定性的。生产环境 `AF_PRODUCTION=true` 时强制要求 https 端点。
- `outputMode=json` 的节点：provider 会自动加 system 提示并使用 `response_format`，返回值解析成 `parsed`。

## 演示脚本（P0 验收路径）

1. 登录 `editor@agentflow.dev` → 新建工作流 `demo`。
2. 画布：start → llm(draft) → end；或打开「DSL」粘贴 `docs/architecture.md` §6.4 的示例（LLM→工具→LLM）→ 应用。
3. Ctrl+S 保存（右上角状态变「已保存」）→「冻结并发布」。
4. 「运行」→ 变量 `{"question": "我的手机 13812345678 无法开机"}` → 触发。
5. 执行详情：节点树实时变亮（SSE），点节点看输入/输出原文——手机号已脱敏为 `[PHONE]`；右栏 token/steps/traceId。
6. 回到执行列表刷新：记录仍在（持久化）。
7. 登录 `beta@agentflow.dev`，用第 5 步 URL 里的 executionId 直接访问 → 404。
8. 登录 `orgadmin@agentflow.dev`（只在 Demo Org 上是 ADMIN）：进入 Space Alpha/Beta 均可读写——三级继承；跨到无关空间仍 404。
9. 「连接器」页（ADMIN）：新建 HTTP 连接器 → 填 `baseUrl`/`allowedHosts` → 授权密钥（只存密文）→ 在工作流 tool 节点引用其 id。指向内网/环回会被沙箱拒绝（`CONN-SANDBOX-001`）。

## 演示脚本（P3 验收路径）

1. 用 `editor@agentflow.dev` 建一个含 `human_gate` 的工作流：`start → transform(prep) → human_gate → (approved) ok / (rejected) no → end`，
   gate 的 `approvers` 填 `{"type":"ROLE","role":"ADMIN"}`，`promptTemplate` 写成 `确认要发布吗：{{ vars.mid }}`，
   `onTimeout` 填 `{"action":"REJECT"}`。冻结并发布 → 触发。
2. 执行详情里执行状态为 **SUSPENDED**，`gate` 节点也是 SUSPENDED（不是 RUNNING/FAILED）。
3. 用 `admin@agentflow.dev` 打开「审批中心」：看到待办（提示词、发起人、倒计时，审批人快照已冻结）→
   填评论→「通过」→ 执行续跑并走 `approved` 分支；「驳回」走 `rejected` 分支，另一分支显式 `NodeSkipped`。
4. 不裁决也能收敛：`onTimeout.action=REJECT` 时，超时后引擎按 `REJECT` 自动裁决（`resolvedBy=system:timeout`）。
5. 「评测」页：建数据集 → 加两个用例（一个能对上、一个对不上）→ 启动评测 → 结果 1 通过 1 失败；
   同一数据集对两个版本各跑一次 → 「A/B」表格给出 `winner` 与双方通过率/耗时/token。

停止：`./scripts/stop-dev.ps1`（`-Infra` 连容器一起停）。

重跑不必先手动停：`start-dev` 在构建前会先执行 `stop-dev` 并清掉残留的半成品 `*.jar.original`。
Windows 上运行中的 jar 是独占打开的，不先停就会在 `spring-boot:repackage` 改名时失败。
`stop-dev` 只停本仓库的进程（按仓库路径判定），不会影响同机其他项目的同名服务。

## 健康检查

每个服务都暴露探活端点，可直接作为容器 liveness / readiness 探针：

| 服务 | 端点 | 反映的依赖 |
|---|---|---|
| agent-gateway :8080 | `GET /health` | Redis（限流、幂等） |
| workflow-service :8084 | `GET /health` | MySQL + Redis |
| connector-service :8085 | `GET /health` | MySQL + Redis |
| audit-service :8086 | `GET /health` | MySQL |
| engine-api :8000 | `GET /internal/health` | 进程存活 |
| engine-api :8000 | `GET /internal/metrics` | Prometheus 文本（需内部令牌） |

返回 200 为 `UP`，503 为 `DEGRADED`（至少一个依赖不可用，不应继续接流量）。生产守护开启时不返回依赖明细，只给 `unavailable`，避免把内部拓扑暴露给未认证调用方。

## 工程门禁

```powershell
./scripts/verify-all.ps1      # 本地三端全量：mvn verify / ruff+pytest / tsc+eslint+vitest+build
```

CI（`.github/workflows/`）：`java-ci`（测试+Checkstyle+ArchUnit）、`python-ci`（Ruff+Pytest+testcontainers PG）、`frontend-ci`（typecheck+lint+vitest+build）、`contract-drift`（引擎 OpenAPI 生成物 vs 入库基线）。

引擎侧新增或改动路由、请求/响应模型后，基线必须同步刷新，否则 `contract-drift` 会红：

```powershell
engine-api/.venv/Scripts/python.exe scripts/dump-openapi.py
git add docs/openapi/engine-api.json
```

## 安全

凭据加密、出网沙箱、多租户隔离等控制的位置与已知限制见 [`SECURITY.md`](SECURITY.md)。漏洞请走私下渠道上报，不要开公开 Issue。

## 环境变量

| 变量 | 默认（本地） | 说明 |
|---|---|---|
| `AF_JWT_SECRET` | dev-only-… | JWT HS256 密钥（≥32 字节），三端共享 |
| `AF_INTERNAL_TOKEN` | dev-internal-token | 服务间内部令牌（/engine/** 与引擎入站） |
| `AF_MYSQL_HOST/PORT/USER/PASSWORD` | 127.0.0.1:3306 root/root | 业务库 |
| `AF_AUDIT_MYSQL_USER` | af_audit | 审计写入账号（仅 SELECT/INSERT audit_event） |
| `AF_PG_DSN` | postgresql://agentflow:agentflow@127.0.0.1:5433/agentflow_events | 事件库 |
| `AF_REDIS_HOST/PORT` / `AF_REDIS_URL` | 127.0.0.1:6379 | 缓存/租约/预算/事件频道 |
| `AF_MQ_ENABLED` / `AF_ROCKETMQ_NAMESRV` | false / 127.0.0.1:9876 | 审计 MQ；false 时审计只写进程日志（每次 emit WARN），需要留痕的环境必须开启 |
| `AF_DEFAULT_TOKEN_BUDGET` / `AF_DEFAULT_MAX_STEPS` / `AF_DEFAULT_DEADLINE_MS` | 50000 / 50 / 120000 | 租户政策上限（触发时钳制） |
| `AF_AUTHORIZED_CONNECTORS` | mock.search,mock.echo,mock.flaky | 内置 mock 连接器（真实连接器由 connector-service 目录自动授权） |
| `AF_CONNECTOR_SERVICE` | http://127.0.0.1:8085 | gateway → connector-service 路由目标 |
| `AF_CRED_MASTER_KEY` | 本地 dev 值（base64 32B） | 凭据主密钥；生产必须经 KMS/环境注入 |
| `AF_CRED_MASTER_KEY_PREVIOUS` | 空 | 轮换窗口内的旧主密钥（base64，逗号分隔），用于解密历史密文；全量重加密后清空 |
| `AF_PRODUCTION` | false | 生产守护开关。满足任一条件即进入守护：`AF_PRODUCTION=true`、`-Dagentflow.production=true`、`AF_ENV=production/staging`、`SPRING_PROFILES_ACTIVE` 含 `prod`。守护期内若仍用 dev 默认密钥/弱口令、未开 DB TLS 或未设 Cookie Secure，启动即 fail-fast；`AF_ENV=dev` 可显式退出守护 |
| `AF_MYSQL_USE_TLS` / `AF_MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL` | false / true | 数据库 TLS；生产守护要求前者为 true，并应显式关闭后者 |
| `AF_SANDBOX_ALLOW_HTTP` / `AF_SANDBOX_ALLOW_LOOPBACK` | false / false | 沙箱是否允许明文 http / 环回地址（仅本地调试开启） |
| `AF_MONTHLY_CALL_LIMIT` / `AF_MONTHLY_TOKEN_LIMIT` | 10000 / 50000 | 成本中心月度初始限额（子租户沿祖先链取最小） |
| `AF_DEMO_ACCOUNTS` | true | 登录页是否下发演示账号提示；生产守护开启时恒不下发 |
| `AF_CHECKPOINT_INTERVAL` | 10 | 每 N 事件一次 CheckpointCreated |
| `AF_GATE_SWEEP_INTERVAL_S` | 15 | human_gate 超时裁决扫描间隔 |
| `AF_LLM_BASE_URL` | 空 | 真实模型端点（OpenAI 兼容，如 `https://api.deepseek.com/v1`）；空则 provider 未启用 |
| `AF_LLM_API_KEY` | 空 | 上游密钥；只在 engine-api 进程内使用，绝不写入日志/事件/仓库 |
| `AF_LLM_MODEL` | gpt-4o-mini | 默认模型；节点里填 `mock-*` 占位名时自动回落到它 |
| `AF_LLM_TIMEOUT_S` / `AF_LLM_MAX_RESPONSE_CHARS` | 60 / 100000 | 单次调用超时、响应文本上限 |
| `AF_LLM_JSON_RESPONSE_FORMAT` | true | 若上游不支持 `response_format` 则置 false |
| `AF_LLM_SEND_SEED` | false | 是否向上游传递确定性 seed（仅部分厂商支持） |
| `AF_LLM_ALLOW_INSECURE_HTTP` | false | 生产守护例外：允许明文 http 端点（仅本地调试） |
| `AF_GATE_RELAY_INTERVAL_MS` | 5000 | 裁决 outbox 重投间隔（引擎不可达时补偿） |
| `AF_EVAL_POLL_INTERVAL_MS` | 3000 | 评测/A-B 结果收口轮询间隔 |
| `AF_RATE_LIMIT_TRIGGER_MAX` / `AF_RATE_LIMIT_LOGIN_MAX` | 20 / 10（start-dev 设 300） | 网关固定窗口限流（次/60s/用户｜IP） |
| `AF_LEASE_TTL_MS` / `AF_LEASE_RENEW_MS` | 30000 / 10000 | 执行租约 |
| `AF_GATEWAY_BASE` | http://127.0.0.1:8080 | workflow-service → gateway |
| `AF_ENGINE_SERVICE` | http://127.0.0.1:8000 | gateway /engine 路由目标 |

仓库内无任何真实密钥；以上默认值仅供本地开发。

## 已知限制

1. 审计经 MQ 异步落库；`AF_MQ_ENABLED=false` 时审计只写进程日志（每次 emit 一条 WARN），outbox + relay 仍未落地。需要留痕的环境必须开启 MQ。
2. 预算预占的崩溃泄漏窗口已由 checkpoint/恢复对账收敛（`BudgetLedger.reconcile`）；Redis 全部丢失时会从事件流重建。
3. 沙箱做「域名白名单 + 解析期私网拦截」，未做 DNS 钉扎（rebinding 由生产 egress 代理兜底）。
4. RocketMQ 的 Python 消费端未启用（引擎不消费 MQ）；审批回调走 HTTP + `gate_resolve_outbox` 重投（P3）。
5. Java 侧 OpenAPI 生成物未入库（contract-drift 覆盖引擎侧）。
6. `human_gate` 的审批人 `EXPRESSION` 类型 P3 未支持（ROLE / USERS 已支持）；子图（loop/parallel/subworkflow）内不允许 `human_gate`。
7. 评测与 A/B 用轮询收口（默认 3s），不是事件驱动；用例数上限 50/次。
8. 画布节点面板目前只暴露 `start / llm / tool / end` 四类；`condition / loop / parallel / transform / subworkflow / human_gate` 需在 DSL 面板里编辑（引擎与校验已全量支持）。
9. `retrieval` 只在 DSL 类型与前端枚举里占位，引擎未实现该执行器（阶段门会以 `DSL-NODE-001` 拒绝，不会静默降级）。

## 目录

```
common/ agent-gateway/ workflow-service/ audit-service/   # Java 控制面
engine-api/                                              # Python 执行引擎（数据面）
frontend/                                                # Vue3 + Vue Flow
db/mysql|postgres/                                       # 迁移 + 授权 + 事件库
e2e/                                                     # API 级验收（打 gateway）
ops/ scripts/ docs/                                      # 基础设施 / 脚本 / 架构与契约
```
