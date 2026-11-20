# AgentFlow 架构设计文档

> 版本：v1.0（已确认）｜状态：**架构已锁定，进入 P0 实现**
> 本文档是 P0–P3 全部实现的契约。任何实现与本文件冲突时，先改文档再改代码。

---

## 0. 关键设计决策记录（ADR 摘要）

| # | 决策 | 理由 |
|---|---|---|
| D1 | Java 侧对 Python 的唯一调用路径：workflow-service → gateway 内部路由 `/engine/**` → engine-api。engine-api 仅监听内网网卡，所有 internal 调用要求 `X-Internal-Token` + `traceparent` | 满足 §5.2「唯一调用入口」与 ArchUnit 依赖方向；治理（超时/熔断/deadline）集中在 gateway |
| D2 | 事件回流三通道：① 权威投影 = workflow-service Projector 轮询 engine `GET /internal/events?afterSeq=`（游标存 MySQL `projector_cursor`）；② 实时推送 = engine 追加事件后发布 Redis 频道 `af:exec-events`（at-most-once），SSE Hub 订阅扇出，断线用 `afterSeq` 追平；③ 审计兜底 = Projector 转发 RocketMQ `af.audit.events` | PG 由 Python 独占写入，Java 不直连事件库；SSE 不依赖 MQ 即可低延迟；MQ 只兜审计 |
| D3 | 工具副作用路径：P0–P1 用 engine 内置 `mock.*` 连接器（开发装置，无凭据、无外网，调用计数落 Redis，按 `callId` 去重）；P2 起真实工具调用由 engine 发 `ToolCommand` → gateway → connector-service 执行，**凭据只在 connector-service 解密，Python 永不见明文** | 满足 §5.2「Python 绝不可以直连外部服务明文凭据」，同时让 P0 的 tool 节点可演示 |
| D4 | 崩溃恢复：engine 启动时扫描自有 PG 表 `execution_tracker`（非终态 execution），逐个尝试获取租约续跑；workflow-service 另设兜底任务，对 MySQL 中超期 RUNNING 的执行做幂等 re-drive | 恢复不依赖业务库；双保险 |
| D5 | `execution_event.payload` 口径：`NodeCompleted.payload.output` 存**完整且已脱敏**的输出（重放所需），其余事件存摘要+digest；原始提示词全文、未脱敏内容永不入事件；单事件 payload 上限 256KB，溢出落 `node_io` 并仅在事件存引用+digest | 调和 §6.2「只含摘要」与 §6.3「重放所需全部字段」 |
| D6 | 主键统一 UUID（CHAR(36)）；每张需分页/排序的表另加 `seq BIGINT AUTO_INCREMENT UNIQUE` 作为游标 | UUID 不可排序，cursor 分页需要稳定序 |
| D7 | 软删除唯一约束用 MySQL 8 生成列模式：`uq_x = IF(deleted_at IS NULL, <col>, NULL)`，唯一索引建在生成列上 | MySQL 唯一索引中 NULL 互不相等，普通复合唯一索引无法约束未删除行 |
| D8 | 灰度发布：`workflow_definition.traffic_json = [{versionId, weight}]`，触发时按 `hash(idempotencyKey)` 确定性分桶选版本；NULL = 100% current | 同一幂等键永远路由同一版本，天然防重 |
| D9 | retrieval 节点走 `RetrievalProvider` 抽象；P0 提供 mock provider（离线固定语料），P2 接 pgvector / DocQA RAG 服务 | §4 栈未列 pgvector，P0 须可离线演示 |
| D10 | 表达式引擎用 simpleeval 白名单（禁下划线属性、禁 import、禁 lambda）；模板用 Jinja2 `SandboxedEnvironment`；CI 用 Ruff S 规则 + grep 禁 `eval`/`exec` | §9 安全约束 |
| D11 | 预算硬停止实现：发出任何 Command 前，引擎用 Redis 原子 `INCRBY` 预占 `min(node.maxTokens, 剩余预算)`，并把该 Command 的 `maxTokens` 钳到剩余额度；完成后按实际结算回冲预占 | 保证 `tokenUsed ≤ tokenBudget` 恒成立（US-05） |

---

## 1. 系统架构

```mermaid
flowchart TB
    subgraph FE[前端 :5174]
        VUE[Vue3 + Vue Flow]
    end

    subgraph GW[agent-gateway :8080]
        SEC[认证/租户解析/限流]
        GOV[AI 治理: 预算钳制·KillSwitch·熔断·deadline·trace]
        SSEP[SSE 代理]
    end

    subgraph CP[控制面]
        WF[workflow-service :8084<br/>工作流/版本/执行索引/事件投影/SSE Hub]
        CONN[connector-service :8085<br/>P2: 工具注册/OAuth/凭据/沙箱]
        AUD[audit-service :8086<br/>MQ 消费者 + 审计查询]
    end

    subgraph DP[数据面 内网]
        ENG[engine-api :8000<br/>DSL 编译·拓扑调度·状态机·事件追加·重放]
        MOCK[mock.* 连接器<br/>P0–P1 内置]
    end

    subgraph STORE[存储/中间件]
        MYSQL[(MySQL8<br/>业务库)]
        PG[(PostgreSQL16<br/>event_log append-only)]
        REDIS[(Redis<br/>幂等/租约/预算账本/事件频道)]
        MQ[[RocketMQ5<br/>执行任务·审批回调·审计兜底]]
    end

    LLM{{LLM Provider<br/>P0: mock}}
    TOOL{{外部工具<br/>P2 经 connector-service}}

    VUE -->|/api/v1| GW
    GW --> WF
    GW --> CONN
    GW --> AUD
    WF -->|/engine/** 唯一入口 D1| GW
    GW --> ENG
    ENG -->|ToolCommand P2 起| GW
    ENG --> PG
    ENG --> REDIS
    ENG --> MQ
    ENG --> LLM
    ENG -.->|P0–P1| MOCK
    WF --> MYSQL
    AUD --> MYSQL
    CONN --> MYSQL
    WF -->|轮询事件投影 D2| GW
    REDIS -->|pub/sub| WF
    MQ --> AUD
    CONN --> TOOL
```

组件与端口：

| 组件 | 端口 | 说明 |
|---|---|---|
| agent-gateway | 8080 | 唯一对外入口；`/api/v1/**` 业务路由，`/engine/**` 内部路由（仅服务间） |
| workflow-service | 8084 | 工作流/版本/执行索引/审批/事件投影/SSE Hub |
| connector-service | 8085 | P2 加入；P0–P1 由 engine 内置 mock 连接器替代（D3） |
| audit-service | 8086 | RocketMQ 消费者 + `/admin/audit` 查询 |
| engine-api | 8000 | 仅内网；拒绝无 `X-Internal-Token` 请求 |
| 前端 dev | 5174 | Vite dev server（IPv4 `127.0.0.1`，`strictPort`），代理到 8080；避开常被其他项目占用的 5173 |

---

## 2. 模块边界与依赖方向（硬性）

### 2.1 Java（Maven 多模块）

```
agentflow-parent
├── common            # DTO/错误码/ProblemDetail/trace/tenant/pii-v2/幂等组件/JWT
├── agent-gateway     # 路由、安全过滤器、治理过滤器；只依赖 common
├── workflow-service  # 依赖 common；调用 engine 只能经 gateway（ArchUnit 强制）
└── audit-service     # 依赖 common；MQ 消费者 + 审计查询
```

ArchUnit 规则（CI 执行，违反即红）：

1. `workflow-service` 任何类不得出现 engine-api 主机字面量；对 engine 的 HTTP 调用只能经 `EngineGatewayClient`，其 base URL 只允许指向 gateway。
2. 分层：`controller → service → repo`，反向依赖禁止；`repo` 不得依赖 `service`。
3. `common` 不得依赖任何业务模块。
4. `audit_event` 的写入只能出现在 `audit-service` 的 `..consumer..` 包。
5. Maven 模块间禁止循环依赖。

### 2.2 Python（engine-api 包）

```
app/
├── api/          # FastAPI 路由（/internal/**、health、metrics）——薄层，不含业务
├── dsl/          # Pydantic v2 模型、校验、编译为 DAG
├── engine/       # 调度器、状态机、重放、租约、checkpoint、取消、dispatcher
├── nodes/        # 11 种节点实现；只发 Command，不直接做副作用
├── commands/     # Command 对象 + Provider 注册表 + mock providers
├── events/       # 事件追加（乐观并发）、重放、Redis 发布、tracker
├── governance/   # token 计量、预算账本、PII 第二层
├── expression/   # simpleeval 白名单、Jinja2 沙箱
└── observability/# OTel、Prometheus、结构化日志
```

依赖规则（Ruff/flake8-import-order + 自定义 CI 检查）：`api → engine → nodes → commands → events`；`nodes` 与 `engine` 不得 import FastAPI；PG 写入只能出现在 `events/`；Redis 访问只能出现在 `events/`、`governance/`、`engine/lease.py`。

### 2.3 前端

```
src/
├── api/        # OpenAPI 生成客户端（提交入库）+ SSE 封装
├── dsl/        # DSL TS 类型、Vue Flow ↔ DSL 双向转换、客户端预校验
├── stores/     # auth / workflows / executions / ui
├── views/      # Login, WorkflowList, CanvasEditor, Versions,
│               # ExecutionDetail, ExecutionList, Connectors(P2), Admin
├── components/ # canvas/ nodes/ drawers/ execution/ common/
└── design/     # tokens.css（暗物质：#08080C/#0E0E14/#00E5FF，禁绿色）
```

ESLint + vue-tsc --strict + Vitest；禁止 `any`（确需时行内注释说明）。

---

## 3. 数据模型（完整 DDL）

### 3.1 MySQL（库 `agentflow`，utf8mb4，Flyway 管理，迁移一旦合入禁止修改）

```sql
-- V001__tenant_user_auth.sql ------------------------------------------------
CREATE TABLE tenant (
  id          CHAR(36)     NOT NULL,
  name        VARCHAR(128) NOT NULL,
  type        ENUM('ORG','TEAM','SPACE') NOT NULL,
  parent_id   CHAR(36)     NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at  DATETIME(3)  NULL,
  uq_parent   VARCHAR(36)  GENERATED ALWAYS AS (IF(deleted_at IS NULL, IFNULL(parent_id,''), NULL)) VIRTUAL,
  uq_name     VARCHAR(128) GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_tenant_parent_name (uq_parent, uq_name),
  KEY idx_tenant_parent (parent_id)
);

CREATE TABLE app_user (                                  -- 复用 DocQA 结构
  id            CHAR(36)     NOT NULL,
  email         VARCHAR(255) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,                   -- bcrypt
  display_name  VARCHAR(128) NULL,
  status        ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at    DATETIME(3)  NULL,
  uq_email      VARCHAR(255) GENERATED ALWAYS AS (IF(deleted_at IS NULL, email, NULL)) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_user_email (uq_email)
);

CREATE TABLE refresh_token (                             -- 复用 DocQA 轮换模型
  id         CHAR(36)    NOT NULL,
  user_id    CHAR(36)    NOT NULL,
  token_hash CHAR(64)    NOT NULL,                       -- sha256(token)
  family_id  CHAR(36)    NOT NULL,                       -- 轮换族；重用检测
  expires_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_rt_hash (token_hash),
  KEY idx_rt_user (user_id),
  KEY idx_rt_family (family_id)
);

CREATE TABLE tenant_member (
  tenant_id  CHAR(36) NOT NULL,
  user_id    CHAR(36) NOT NULL,
  role       ENUM('OWNER','ADMIN','EDITOR','VIEWER') NOT NULL,
  inherited  TINYINT(1) NOT NULL DEFAULT 0,              -- 由上级租户继承
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id, user_id),
  KEY idx_tm_user (user_id)
);

-- V002__workflow.sql ------------------------------------------------------------
CREATE TABLE workflow_definition (
  id                 CHAR(36)     NOT NULL,
  tenant_id          CHAR(36)     NOT NULL,              -- 归属 Space
  name               VARCHAR(128) NOT NULL,
  description        VARCHAR(1024) NULL,
  status             ENUM('DRAFT','PUBLISHED','ARCHIVED') NOT NULL DEFAULT 'DRAFT',
  current_version_id CHAR(36)     NULL,                  -- 应用层维护，不加 FK（成环）
  draft_dsl          JSON         NULL,                  -- 可覆盖的草稿
  draft_checksum     CHAR(64)     NULL,
  draft_updated_at   DATETIME(3)  NULL,
  traffic_json       JSON         NULL,                  -- P1 灰度 [{versionId,weight}]
  created_by         CHAR(36)     NOT NULL,
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at         DATETIME(3)  NULL,
  uq_name            VARCHAR(128) GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_wf_tenant_name (tenant_id, uq_name),
  KEY idx_wf_tenant_status (tenant_id, status),
  KEY idx_wf_current_version (current_version_id)
);

CREATE TABLE workflow_version (
  id            CHAR(36)    NOT NULL,
  definition_id CHAR(36)    NOT NULL,
  version_no    INT         NOT NULL,
  dsl_json      JSON        NOT NULL,
  dsl_checksum  CHAR(64)    NOT NULL,                    -- sha256(canonical(dsl_json))
  immutable     TINYINT(1)  NOT NULL DEFAULT 1,          -- 冻结即不可变
  published_at  DATETIME(3) NULL,
  published_by  CHAR(36)    NULL,
  created_by    CHAR(36)    NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_wv_def_ver (definition_id, version_no),
  KEY idx_wv_def (definition_id),
  KEY idx_wv_checksum (dsl_checksum),
  CONSTRAINT fk_wv_def FOREIGN KEY (definition_id) REFERENCES workflow_definition(id)
);

DELIMITER $$
CREATE TRIGGER trg_wv_no_update BEFORE UPDATE ON workflow_version FOR EACH ROW
BEGIN
  IF OLD.immutable = 1 AND (
       NOT (NEW.dsl_json <=> OLD.dsl_json)
       OR NEW.version_no <> OLD.version_no
       OR NEW.dsl_checksum <> OLD.dsl_checksum
       OR NEW.definition_id <> OLD.definition_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'WF-IMMUTABLE-001';
  END IF;
END$$
CREATE TRIGGER trg_wv_no_delete BEFORE DELETE ON workflow_version FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'WF-IMMUTABLE-001';
END$$
DELIMITER ;

-- V003__execution.sql -----------------------------------------------------------
CREATE TABLE workflow_execution (
  seq             BIGINT AUTO_INCREMENT UNIQUE,          -- 游标
  id              CHAR(36) NOT NULL,
  definition_id   CHAR(36) NOT NULL,
  version_id      CHAR(36) NOT NULL,
  tenant_id       CHAR(36) NOT NULL,
  triggered_by    CHAR(36) NOT NULL,
  status          ENUM('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')
                  NOT NULL DEFAULT 'PENDING',
  idempotency_key VARCHAR(128) NOT NULL,
  trace_id        VARCHAR(64)  NOT NULL,
  token_budget    INT NULL,
  max_steps       INT NULL,
  deadline_at     DATETIME(3) NULL,
  token_used      INT NOT NULL DEFAULT 0,
  steps_used      INT NOT NULL DEFAULT 0,
  error_code      VARCHAR(64) NULL,
  started_at      DATETIME(3) NULL,
  finished_at     DATETIME(3) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_we_tenant_idem (tenant_id, idempotency_key),
  KEY idx_we_tenant_status (tenant_id, status, seq),
  KEY idx_we_def (definition_id, seq),
  KEY idx_we_trace (trace_id)
);

CREATE TABLE node_execution (
  seq           BIGINT AUTO_INCREMENT UNIQUE,
  id            CHAR(36) NOT NULL,
  execution_id  CHAR(36) NOT NULL,
  node_key      VARCHAR(160) NOT NULL,                   -- 循环迭代: loopKey#i/innerKey
  display_order INT NOT NULL,
  status        ENUM('PENDING','SCHEDULED','RUNNING','SUCCEEDED','FAILED',
                     'SKIPPED','SUSPENDED','CANCELLED') NOT NULL DEFAULT 'PENDING',
  attempt       INT NOT NULL DEFAULT 0,
  input_digest  CHAR(64) NULL,
  output_digest CHAR(64) NULL,
  token_used    INT NOT NULL DEFAULT 0,
  latency_ms    INT NULL,
  retry_count   INT NOT NULL DEFAULT 0,
  error_code    VARCHAR(64) NULL,
  started_at    DATETIME(3) NULL,
  finished_at   DATETIME(3) NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ne_exec_node_attempt (execution_id, node_key, attempt),
  KEY idx_ne_exec_order (execution_id, display_order),
  KEY idx_ne_exec_status (execution_id, status)
);

-- V004__governance.sql ----------------------------------------------------------
CREATE TABLE idempotency_key (
  tenant_id  CHAR(36)     NOT NULL,
  scope      VARCHAR(64)  NOT NULL,                      -- 如 execute / connector-call
  ikey       VARCHAR(128) NOT NULL,
  result_json JSON        NULL,                          -- 首个请求的结果快照
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3)  NOT NULL,
  PRIMARY KEY (tenant_id, scope, ikey),
  KEY idx_ik_expires (expires_at)
);

CREATE TABLE audit_event (
  seq              BIGINT AUTO_INCREMENT UNIQUE,
  id               CHAR(36) NOT NULL,
  tenant_id        CHAR(36) NOT NULL,
  trace_id         VARCHAR(64) NOT NULL,
  execution_id     CHAR(36) NULL,
  actor_id         CHAR(36) NULL,
  event_type       VARCHAR(64) NOT NULL,
  payload_redacted JSON NULL,                            -- 已脱敏
  token_used       INT NULL,
  latency_ms       INT NULL,
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ae_tenant (tenant_id, seq),
  KEY idx_ae_trace (trace_id),
  KEY idx_ae_exec (execution_id)
);
-- 只追加约束（双重）：
--  1) 授权：应用账号 af_app 对 audit_event 仅 SELECT/INSERT；写入走独立账号 af_audit
--     GRANT SELECT, INSERT ON agentflow.audit_event TO 'af_audit'@'%';
--     （af_app 的授权按表逐一授予，不含 audit_event 的 UPDATE/DELETE）
--  2) 触发器兜底：
DELIMITER $$
CREATE TRIGGER trg_ae_no_update BEFORE UPDATE ON audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUD-APPENDONLY-001'; END$$
CREATE TRIGGER trg_ae_no_delete BEFORE DELETE ON audit_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUD-APPENDONLY-001'; END$$
DELIMITER ;

CREATE TABLE budget_ledger (
  tenant_id    CHAR(36)  NOT NULL,
  period       CHAR(7)   NOT NULL,                       -- YYYY-MM
  calls_used   INT       NOT NULL DEFAULT 0,
  tokens_used  BIGINT    NOT NULL DEFAULT 0,
  limit_calls  INT       NOT NULL,
  limit_tokens BIGINT    NOT NULL,
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id, period)
);

CREATE TABLE projector_cursor (
  projector  VARCHAR(64) NOT NULL,
  last_seq   BIGINT      NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (projector)
);

-- V005__connector.sql（P2）-------------------------------------------------------
CREATE TABLE tool_connector (
  id          CHAR(36)     NOT NULL,
  tenant_id   CHAR(36)     NOT NULL,
  name        VARCHAR(128) NOT NULL,
  type        VARCHAR(64)  NOT NULL,
  auth_type   ENUM('NONE','API_KEY','BASIC','OAUTH2') NOT NULL DEFAULT 'NONE',
  config_json JSON         NULL,                         -- 参数 schema 等，不含密钥
  enabled     TINYINT(1)   NOT NULL DEFAULT 1,
  created_by  CHAR(36)     NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at  DATETIME(3)  NULL,
  uq_name     VARCHAR(128) GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_tc_tenant_name (tenant_id, uq_name)
);

CREATE TABLE tool_credential (
  id               CHAR(36) NOT NULL,
  connector_id     CHAR(36) NOT NULL,
  tenant_id        CHAR(36) NOT NULL,
  encrypted_secret VARBINARY(2048) NULL,                 -- AES-256-GCM
  oauth_token_enc  VARBINARY(4096) NULL,
  key_id           VARCHAR(64) NOT NULL,                 -- 主密钥版本
  expires_at       DATETIME(3) NULL,
  scope            VARCHAR(255) NULL,
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_tcr_conn (connector_id)
);

-- V006__notification.sql（P3）----------------------------------------------------
CREATE TABLE notification (
  seq          BIGINT AUTO_INCREMENT UNIQUE,
  id           CHAR(36) NOT NULL,
  tenant_id    CHAR(36) NOT NULL,
  user_id      CHAR(36) NOT NULL,
  type         VARCHAR(64) NOT NULL,                     -- GATE_PENDING 等
  payload_json JSON NULL,
  read_at      DATETIME(3) NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ntf_user (user_id, read_at)
);
```

### 3.2 PostgreSQL（库 `agentflow_events`，独立迁移，Python 独占写入）

```sql
-- V001__event_log.sql -----------------------------------------------------------
CREATE TABLE execution_event (
  seq               BIGSERIAL PRIMARY KEY,               -- 全局序（projector 游标）
  execution_id      UUID        NOT NULL,
  event_type        TEXT        NOT NULL,                -- 见 §5.2 事件目录
  node_key          TEXT        NULL,
  payload           JSONB       NOT NULL,                -- 口径见 D5
  aggregate_version INT         NOT NULL,                -- 每 execution 严格递增
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_ee_version ON execution_event(execution_id, aggregate_version);
CREATE INDEX        idx_ee_exec    ON execution_event(execution_id, seq);

CREATE TABLE execution_tracker (                          -- 恢复扫描用，与事件同 TX 更新
  execution_id UUID PRIMARY KEY,
  status       TEXT NOT NULL,                             -- OPEN / TERMINAL
  last_seq     BIGINT NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_et_open ON execution_tracker(updated_at) WHERE status = 'OPEN';

CREATE TABLE node_io (
  execution_id UUID NOT NULL,
  node_key     TEXT NOT NULL,
  direction    TEXT NOT NULL CHECK (direction IN ('IN','OUT')),
  payload      JSONB NOT NULL,                            -- 脱敏后原文
  content_hash TEXT NOT NULL,                             -- sha256
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (execution_id, node_key, direction)
);
-- 授权：engine 账号对 execution_event 仅 SELECT/INSERT（DB 层保证 append-only）；
-- tracker/node_io 允许 UPDATE（tracker 与 node_io 的 upsert）。
```

**事件即唯一真相的执行纪律**：写事件 → 应用状态 → 对外可见；禁止反向。tracker 与事件追加在同一事务。`node_io` 仅租户内可访问（gateway 校验后由 engine 内网接口返回）。

---

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

## 5. 执行引擎（核心）

### 5.1 状态机

**Execution：**

```mermaid
stateDiagram-v2
  [*] --> PENDING: ExecutionCreated
  PENDING --> RUNNING: ExecutionStarted<br/>(租约获取成功)
  PENDING --> TIMED_OUT: ExecutionTimedOut<br/>(队列中超 deadline)
  RUNNING --> SUCCEEDED: ExecutionCompleted<br/>(所有必需节点终态)
  RUNNING --> FAILED: ExecutionFailed<br/>(永久失败/预算耗尽/循环超限)
  RUNNING --> TIMED_OUT: ExecutionTimedOut<br/>(deadline 到达)
  RUNNING --> CANCELLED: ExecutionCancelled<br/>(取消请求)
  SUCCEEDED --> [*]
  FAILED --> [*]
  TIMED_OUT --> [*]
  CANCELLED --> [*]
```

**Node Execution：**

```mermaid
stateDiagram-v2
  [*] --> PENDING: 图实例化
  PENDING --> SCHEDULED: 入边全满足 且 并行槽可用
  PENDING --> SKIPPED: 分支未命中/上游被跳过
  SCHEDULED --> RUNNING: 执行器拾取
  SCHEDULED --> CANCELLED: 执行被取消
  RUNNING --> SUCCEEDED: NodeCompleted
  RUNNING --> FAILED: 永久错误 或 重试耗尽
  RUNNING --> SUSPENDED: GateSuspended(human_gate)
  RUNNING --> CANCELLED: 执行被取消
  FAILED --> RUNNING: NodeRetryScheduled<br/>(仅瞬时错误, 新 attempt, ≤3 次)
  SUSPENDED --> RUNNING: GateResolved(approve/reject/超时默认)
  SUSPENDED --> CANCELLED: 执行被取消
```

转移守卫与纪律：

- 终态（SUCCEEDED/FAILED/CANCELLED/TIMED_OUT；节点另含 SKIPPED）吸收一切后续转移。
- **禁止 FAILED → RUNNING 直接跳转**：必须落 `NodeRetryScheduled` 事件并创建新 attempt（新 `node_execution` 行，`(execution_id, node_key, attempt)` 唯一）。
- 非法转移：拒绝 + 审计 `WF-STATE-001`。
- human_gate 无论 approve/reject 都经 RUNNING → SUCCEEDED，输出 `decision`，下游按 `branch=approved|rejected` 的边分流（与 condition 语义统一）。
- 执行被取消时，未开始的节点转 CANCELLED；运行中节点允许当前 Command 收尾后停。

### 5.2 事件目录（§6.2 核心集 + 扩展事件（标 `ex`，均有条款依据））

| 事件 | 级别 | payload 关键字段 | 依据 |
|---|---|---|---|
| ExecutionCreated | 执行 | definitionId, versionId, tenantId/spaceId, triggeredBy, idempotencyKey, policy{tokenBudget,maxSteps,deadlineMs,clamped}, variablesDigest, traceId | 核心 |
| ExecutionStarted `ex` | 执行 | workerId, leaseId, startedAt | 恢复/租约观测 |
| ExecutionCompleted | 执行 | result, tokenUsed, stepsUsed, durationMs | 核心 |
| ExecutionFailed | 执行 | errorCode, message(脱敏), failedNodeKey, tokenUsed, stepsUsed | 核心 |
| ExecutionTimedOut `ex` | 执行 | phase(QUEUE/RUNNING), deadlineAt | 状态机完备性 |
| ExecutionCancelled | 执行 | requestedBy, reason | 核心 |
| CheckpointCreated | 执行 | completedNodes[], variablesSnapshot(脱敏), tokenUsed, stepsUsed | 核心（每 10 事件） |
| NodeScheduled | 节点 | nodeKey, nodeType, attempt, dependsOn[] | 核心 |
| NodeStarted | 节点 | nodeKey, attempt, inputDigest | 核心 |
| NodeCompleted | 节点 | nodeKey, attempt, **output（完整·已脱敏，D5）**, outputDigest, tokenUsed, latencyMs | 核心 |
| NodeFailed | 节点 | nodeKey, attempt, errorCode, class(PERMANENT/TRANSIENT), retryable, latencyMs | 核心 |
| NodeRetryScheduled `ex` | 节点 | nodeKey, nextAttempt, delayMs, cause | §8.4 重试 |
| NodeSkipped `ex` | 节点 | nodeKey, reason(BRANCH_NOT_TAKEN/UPSTREAM_SKIPPED), byNodeKey | US-03 SKIPPED 必须可见 |
| NodeSuspended `ex` | 节点 | nodeKey, reason | 状态机完备性 |
| GateSuspended | 节点 | nodeKey, approversRule, timeoutAt, defaultBranch | 核心（P3） |
| GateResolved | 节点 | nodeKey, decision(APPROVED/REJECTED/TIMEOUT), resolvedBy | 核心（P3） |
| CommandIssued `ex` | 命令 | nodeKey, attempt, **callId**, kind(LLM/TOOL/RETRIEVAL), requestDigest | §8.2.3 命令成对落事件 |
| CommandCompleted `ex` | 命令 | callId, responseDigest, tokenUsed, latencyMs | §8.2.3 |
| CommandFailed `ex` | 命令 | callId, errorCode, class, latencyMs | §8.2.3 |

`aggregate_version` 每 execution 从 1 严格递增；追加时带期望值，冲突则重读重放后重试 ≤3 次。`callId = {executionId}/{nodeKey}/{attempt}`。

### 5.3 确定性重放协议

1. 恢复流程：取租约 → 按 `aggregate_version` 顺序读全部事件 → 还原内存状态（已完成节点输出、变量快照、预算消耗、取消标记）→ 从最近 Checkpoint 加速 → 续跑。
2. 重放时：已 `NodeCompleted` 的节点**直接注入事件中的 output**，不调用模型/工具；有 `CommandCompleted` 的 callId 复用结果；有 `CommandIssued` 无 `CommandCompleted` 的，用**同一 callId** 重发（下游幂等去重）。
3. 恰好一次的论证：副作用发出前必落 `CommandIssued`；callId 确定性派生；mock/connector 按 callId 去重（Redis `SET NX` + 结果缓存）；事件追加有乐观锁兜底；租约只做冲突降级，不做正确性保证。
4. 节点内禁止 `Date.now()`/`random()`/读环境变量：时间由 `ExecutionContext.now()` 注入，随机数由 `sha256(executionSeed + nodeKey + attempt)` 派生。
5. 取消协议：取消请求 → 控制面写 MySQL + 调 engine `/cancel` → engine 落 `ExecutionCancelled`（若租约不在手则经 Redis 取消标记 + 兜底 re-drive）；执行器在节点边界与 Command 发出前检查。

### 5.4 调度语义

- 编译期：DSL → DAG；校验环、悬空引用、未定义变量、重复 nodeKey、非法分支；失败 422 并给出节点路径（错误码见 §8）。
- 运行期：拓扑分层；同层并行受 `maxParallelism`（默认 4）；入边全满足才 `NodeScheduled`。
- condition：仅命中分支的出边激活；未命中分支下游传播 SKIPPED（保留在执行树）。
- loop：迭代实例键 `loopKey#i`，体内节点键 `loopKey#i/innerKey`；超 `maxIterations` 即 `FAILED` + `EXEC-LOOP_LIMIT_EXCEEDED`，已完成迭代结果保留在事件中。
- parallel：分支并发受 `maxConcurrency`；join=`all` 等待全部，`first_success` 首个成功即合并、其余分支允许收尾后忽略（不再发新 Command）。
- subworkflow：子执行是独立 execution（独立预算，父级扣减 `maxTokens` 上界），父节点等待子执行终态。
- 永久/瞬时错误分类：永久（DSL/参数/权限/PII 命中/表达式错误）不重试；瞬时（超时/限流/5xx）指数退避 ≤3 次。节点 `timeoutMs` 与执行 `deadlineMs` 双约束，到期前停止发新 Command。

### 5.5 租约

`af:lease:{executionId}` = `SET NX PX=30000`，value=workerId+leaseId；每 10s 续约；**每次事件追加前**校验租约仍归属自己（GET 比对）；租约丢失 → 停止写事件并退出。正确性兜底是 `aggregate_version` 乐观锁（US-04 租约冲突场景）。

### 5.6 预算硬停止（D11 细化）

发出 Command 前：`remaining = tokenBudget - tokenUsed`；`remaining ≤ 0` → `ExecutionFailed{errorCode: EXEC-BUDGET_EXHAUSTED}`，不再发任何 Command；否则该 Command 的 `maxTokens := min(node.maxTokens, remaining)`，Redis 预占；完成时按实际 `tokenUsed` 结算回冲。MySQL `budget_ledger` 由 projector 异步对账。

### 5.7 LLM Provider（真实模型）

`LlmExecutor` 只依赖 `LLMProvider` 协议（`complete(...) -> CommandResult`），注册表在应用装配期注入：

| provider | 端点来源 | 语义 |
|---|---|---|
| `openai-compatible` | 仅来自 `AF_LLM_*` 环境变量 | 任意 OpenAI 兼容 `/chat/completions`（DeepSeek / Qwen / OpenAI / 本地 Ollama 等） |
| `connector` | 节点配置 `connectionId` 指向的模型连接 | 引擎 → connector-service `/internal/connectors/{id}/invoke` 的 `chat` 操作；端点与密钥由服务端解密注入 |

产品装配只注册上面两条路径；离线 provider 已从 `app/` 移除，单元测试用 `tests/support/` 里的夹具自行注入。

**模型连接（三路径中的首选）**：`tool_connector` 里 `type=LLM` 的一条记录即一条模型连接，
`config` 声明 `baseUrl` / `allowedHosts` / `auth{headerName,valuePrefix}` /
`operations{chat,models}`，密钥走既有的 `tool_credential`（AES-256-GCM，AAD 绑定
`connectorId|tenantId`）。节点只持 `connectionId`，因此：

- 密钥与 URL 不进 DSL、不进画布、不进事件载荷；换密钥或换端点不需要动工作流、不需要重启引擎。
- 出网仍过 `SandboxPolicy`（白名单 + 私网拦截 + DNS rebinding 复核），与工具连接器同一套口径。
- `callId` 由引擎按 `execution_id|node_key|attempt` 确定性派生，崩溃重放会命中
  connector-service 的 `af:conncall:*` 幂等缓存，不重复出网（把 §5.3 的 at-least-once 拉回接近一次）。
- 模型清单由 `POST /api/v1/connectors/{id}/models/probe` 现场拉取，服务端解密凭据后请求一次
  `/models`，响应只含模型 id；失败按错误码分类（4xx/5xx/凭据缺失/沙箱拦截/非 OpenAI 格式）。

信任边界：

- **端点与密钥绝不来自 DSL 或用户入参**：走连接时 URL 与凭据只存在于 connector-service 的记录里（密文），
  走进程级配置时只在 engine-api 装配期从环境变量读取。两条路径都不允许调用方指定 URL，杜绝 SSRF 与凭据注入；
  密钥仅出现在 `Authorization` 头，日志/错误体/事件载荷一律脱敏（单测覆盖）。
- `AF_PRODUCTION=true` 时强制要求 https 端点（`AF_LLM_ALLOW_INSECURE_HTTP` 仅本地调试例外）。
- PII 第二层脱敏仍在 `LlmExecutor` 内先于 provider 执行；provider 不感知原始 prompt 之外的任何租户数据。
- 输入侧历史占位模型名自动回落（进程级回落到 `AF_LLM_MODEL`，连接回落到该连接的 `defaultModel`），避免旧草稿切到真实端点时上游 400。
- 回落只改变实际下发的模型名，不改变审计事实：`output.model` 与 `CommandCompleted` 记录**真实**模型名；声明值与实际值不一致时另存 `requestedModel`，事后可追「配了什么、跑了什么」。
- 连接器错误码在执行详情里翻译成模型语义（`CONN-CREDENTIAL-*` → `LLM-CREDENTIAL-001`、`CONN-NOT_FOUND/DISABLED/SANDBOX` → `LLM-CONNECTION-001`、`CONN-HTTP-5XX/TIMEOUT` → 瞬时），避免把内部编码暴露给使用者，同时保证瞬时性判定正确。

**模型清单**：画布有两处来源。走连接时由 `POST /api/v1/connectors/{id}/models/probe` 现场拉取（服务端解密凭据后请求该连接的 `/models`）；走进程级时由引擎 `GET /internal/llm/models` 提供，经 workflow-service 转发为 `GET /api/v1/llm/models`（JWT 保护）。两处响应都只含模型 id 与默认模型名；未配置端点时返回 `configured=false` 而非报错，上游不可用时返回错误码让前端退回手填。密钥与 base_url 永不出现在响应里，也不会被拼进任何 URL 由调用方指定。

**重试分层**：provider 不做重试，只把上游状态精确分类为瞬时/永久（`LLM-RATE_LIMIT-001` / `LLM-TIMEOUT-001` / `LLM-UPSTREAM-001` 为瞬时；`LLM-AUTH-001` / `LLM-REQUEST-001` / `LLM-RESPONSE-001` / `LLM-NOT_CONFIGURED-001` 为永久）。重试由 §5.4 的引擎 RetryPolicy 统一执行，避免双层重试导致重复计费。

**与 §5.3 的差异（必须知晓）**：真实模型是外部非确定性副作用，且上游普遍不支持按 callId 幂等去重。因此 §5.3 的「恰好一次」论断对真实 provider 降级为 **at-least-once**：`CommandIssued` 已落但 `CommandCompleted` 未落时崩溃恢复会重发调用，可能产生重复计费。`AF_LLM_SEND_SEED=true` 可向上游传递确定性 seed 提高复现性，但不构成幂等保证。需要强幂等时应接入支持 idempotency key 的上游，或在 connector 层加结果缓存。

---

## 6. DSL 规范

### 6.1 设计原则

- 单文件 JSON，`dslVersion` 显式声明；画布坐标等 UI 元数据放 `position`，引擎忽略。
- 变量引用统一两种形态：模板字符串 `"{{ nodes.a.output.text }}"`（Jinja2 沙箱）与受限表达式 `vars.score > 0.5`（simpleeval 白名单）。
- 循环/并行的子图复用同一 `graph` 结构递归定义。
- 表达式可用名：`vars.*`、`nodes.<key>.output.*`、`nodes.<key>.status`、`loop.item/index`（循环体内）；白名单函数：`len upper lower contains startsWith endsWith int float str bool abs min max round json_get`。**禁属性下划线访问、禁 import、禁任意 eval。**

### 6.2 完整 JSON Schema（草案 v1.0）

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://agentflow/schemas/workflow-dsl/1.0.json",
  "title": "AgentFlow Workflow DSL v1.0",
  "type": "object",
  "required": ["dslVersion", "nodes", "edges"],
  "additionalProperties": false,
  "properties": {
    "dslVersion": { "const": "1.0" },
    "variables": {
      "type": "object",
      "additionalProperties": {
        "type": "object",
        "required": ["type"],
        "additionalProperties": false,
        "properties": {
          "type": { "enum": ["string","number","integer","boolean","object","array"] },
          "required": { "type": "boolean", "default": false },
          "default": {},
          "pii": { "type": "boolean", "default": false },
          "description": { "type": "string" }
        }
      }
    },
    "defaults": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "timeoutMs": { "type": "integer", "minimum": 100, "maximum": 600000, "default": 30000 },
        "maxParallelism": { "type": "integer", "minimum": 1, "maximum": 32, "default": 4 },
        "retry": { "$ref": "#/$defs/retry" }
      }
    },
    "nodes": { "type": "array", "minItems": 2, "items": { "$ref": "#/$defs/node" } },
    "edges": { "type": "array", "items": { "$ref": "#/$defs/edge" } }
  },
  "$defs": {
    "nodeKey": { "type": "string", "pattern": "^[a-z][a-zA-Z0-9_-]{0,63}$" },
    "retry": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "maxAttempts": { "type": "integer", "minimum": 1, "maximum": 5, "default": 3 },
        "backoffMs": { "type": "integer", "minimum": 100, "default": 1000 },
        "backoffMultiplier": { "type": "number", "minimum": 1, "default": 2.0 }
      }
    },
    "graph": {
      "type": "object",
      "required": ["nodes", "edges"],
      "additionalProperties": false,
      "properties": {
        "nodes": { "type": "array", "minItems": 1, "items": { "$ref": "#/$defs/node" } },
        "edges": { "type": "array", "items": { "$ref": "#/$defs/edge" } }
      }
    },
    "edge": {
      "type": "object",
      "required": ["from", "to"],
      "additionalProperties": false,
      "properties": {
        "from": { "$ref": "#/$defs/nodeKey" },
        "to": { "$ref": "#/$defs/nodeKey" },
        "branch": { "type": "string", "description": "condition/human_gate 出边的分支名" }
      }
    },
    "node": {
      "type": "object",
      "required": ["key", "type", "config"],
      "additionalProperties": false,
      "properties": {
        "key": { "$ref": "#/$defs/nodeKey" },
        "type": { "enum": ["start","end","llm","retrieval","tool","condition",
                            "loop","parallel","transform","subworkflow","human_gate"] },
        "name": { "type": "string" },
        "timeoutMs": { "type": "integer", "minimum": 100, "maximum": 600000 },
        "retry": { "$ref": "#/$defs/retry" },
        "position": { "type": "object",
                      "properties": { "x": { "type": "number" }, "y": { "type": "number" } } },
        "config": { "type": "object" }
      },
      "allOf": [
        { "if": { "properties": { "type": { "const": "start" } } },
          "then": { "properties": { "config": { "type": "object", "maxProperties": 0 } } } },
        { "if": { "properties": { "type": { "const": "end" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["resultMapping"], "additionalProperties": false,
            "properties": { "resultMapping": { "type": "object", "minProperties": 1,
              "additionalProperties": { "type": "string" } } } } } } },
        { "if": { "properties": { "type": { "const": "llm" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["model","promptTemplate"], "additionalProperties": false,
            "properties": {
              "provider": { "const": "openai-compatible", "default": "openai-compatible" },
              "connectionId": { "type": "string", "minLength": 1,
                "description": "tool_connector 中 type=LLM 的记录 id；端点与密钥只在 connector-service，DSL 不含 URL/Key" },
              "model": { "type": "string", "minLength": 1 },
              "promptTemplate": { "type": "string", "minLength": 1 },
              "systemPrompt": { "type": "string" },
              "temperature": { "type": "number", "minimum": 0, "maximum": 2, "default": 0.7 },
              "maxTokens": { "type": "integer", "minimum": 1, "maximum": 32000, "default": 1024 },
              "outputMode": { "enum": ["text","json"], "default": "text" },
              "outputSchema": { "type": "object",
                "description": "outputMode=json 时必填的 JSON Schema" }
            } } } } },
        { "if": { "properties": { "type": { "const": "retrieval" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["knowledgeBaseIds","queryTemplate"],
            "additionalProperties": false,
            "properties": {
              "knowledgeBaseIds": { "type": "array", "minItems": 1,
                "items": { "type": "string" } },
              "queryTemplate": { "type": "string", "minLength": 1 },
              "topK": { "type": "integer", "minimum": 1, "maximum": 50, "default": 5 },
              "minScore": { "type": "number", "minimum": 0, "maximum": 1, "default": 0 },
              "rerank": { "type": "boolean", "default": false }
            } } } } },
        { "if": { "properties": { "type": { "const": "tool" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["connectorId","operation"],
            "additionalProperties": false,
            "properties": {
              "connectorId": { "type": "string", "minLength": 1 },
              "operation": { "type": "string", "minLength": 1 },
              "paramsMapping": { "type": "object",
                "additionalProperties": { "type": "string" } },
              "idempotent": { "type": "boolean", "default": true,
                "description": "false 时禁止重试" }
            } } } } },
        { "if": { "properties": { "type": { "const": "condition" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["cases"], "additionalProperties": false,
            "properties": {
              "cases": { "type": "array", "minItems": 1, "items": {
                "type": "object", "required": ["name","when"], "additionalProperties": false,
                "properties": { "name": { "type": "string" },
                                "when": { "type": "string" } } } },
              "elseBranch": { "type": "string", "default": "default" }
            } } } } },
        { "if": { "properties": { "type": { "const": "loop" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["itemsExpression","body"],
            "additionalProperties": false,
            "properties": {
              "itemsExpression": { "type": "string" },
              "itemVar": { "type": "string", "default": "item" },
              "indexVar": { "type": "string", "default": "index" },
              "body": { "$ref": "#/$defs/graph" },
              "maxIterations": { "type": "integer", "minimum": 1, "maximum": 1000, "default": 100 },
              "concurrency": { "type": "integer", "minimum": 1, "maximum": 16, "default": 1 },
              "collect": { "type": "object", "required": ["into","item"],
                "additionalProperties": false,
                "properties": { "into": { "type": "string" },
                                "item": { "type": "string" } } }
            } } } } },
        { "if": { "properties": { "type": { "const": "parallel" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["branches"], "additionalProperties": false,
            "properties": {
              "branches": { "type": "object", "minProperties": 2,
                "additionalProperties": { "$ref": "#/$defs/graph" } },
              "join": { "enum": ["all","first_success"], "default": "all" },
              "maxConcurrency": { "type": "integer", "minimum": 1, "maximum": 16, "default": 4 }
            } } } } },
        { "if": { "properties": { "type": { "const": "transform" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["mappings"], "additionalProperties": false,
            "properties": {
              "mappings": { "type": "array", "minItems": 1, "items": {
                "type": "object", "required": ["target","template"],
                "additionalProperties": false,
                "properties": {
                  "target": { "type": "string", "pattern": "^vars\\." },
                  "template": { "type": "string" } } } } }
            } } } } },
        { "if": { "properties": { "type": { "const": "subworkflow" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["definitionId"], "additionalProperties": false,
            "properties": {
              "definitionId": { "type": "string", "minLength": 1 },
              "versionPolicy": { "enum": ["LATEST_PUBLISHED","FIXED"],
                "default": "LATEST_PUBLISHED" },
              "versionId": { "type": "string",
                "description": "versionPolicy=FIXED 时必填" },
              "inputMapping": { "type": "object",
                "additionalProperties": { "type": "string" } },
              "outputMapping": { "type": "object",
                "additionalProperties": { "type": "string" } }
            } } } } },
        { "if": { "properties": { "type": { "const": "human_gate" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["approvers","promptTemplate"],
            "additionalProperties": false,
            "properties": {
              "approvers": { "type": "object", "required": ["type"],
                "additionalProperties": false,
                "properties": {
                  "type": { "enum": ["ROLE","USERS","EXPRESSION"] },
                  "role": { "enum": ["OWNER","ADMIN","EDITOR","VIEWER"] },
                  "userIds": { "type": "array", "items": { "type": "string" } },
                  "expression": { "type": "string" } } },
              "promptTemplate": { "type": "string", "minLength": 1 },
              "timeoutMs": { "type": "integer", "minimum": 60000,
                "maximum": 604800000, "default": 86400000 },
              "onTimeout": { "type": "object", "required": ["action"],
                "additionalProperties": false,
                "properties": {
                  "action": { "enum": ["REJECT","DEFAULT_BRANCH"] },
                  "defaultBranch": { "type": "string" } } }
            } } } } }
      ]
    }
  }
}
```

### 6.3 编译期校验规则（Schema 之外，422 + 节点路径）

| 规则 | 错误码 |
|---|---|
| JSON Schema 不通过 | `DSL-VALIDATION-001` |
| 重复 nodeKey | `DSL-VALIDATION-001`（指出冲突 key） |
| 边引用了不存在的节点 | `DSL-REF-001` |
| 图有环（含循环体内部） | `DSL-CYCLE-001`（列出环上 nodeKey） |
| 引用未声明变量 / 引用了非上游节点输出 | `DSL-REF-001` |
| 悬空节点（start 不可达） | `DSL-ORPHAN-001` |
| condition/human_gate 出边 branch 与 cases 不匹配 | `DSL-BRANCH-001` |
| 恰好 1 个 start、≥1 个 end；loop body 内禁止 start/end/subworkflow 指向自身定义 | `DSL-VALIDATION-001` |
| `outputMode=json` 但缺 `outputSchema`；`versionPolicy=FIXED` 但缺 `versionId` | `DSL-VALIDATION-001` |
| 循环体节点引用体外的兄弟节点（允许引用祖先与 vars） | `DSL-REF-001` |

### 6.4 示例（P0 演示工作流「LLM → 工具 → LLM」）

```json
{
  "dslVersion": "1.0",
  "variables": { "question": { "type": "string", "required": true, "pii": true } },
  "defaults": { "timeoutMs": 30000, "maxParallelism": 4,
                "retry": { "maxAttempts": 3, "backoffMs": 1000, "backoffMultiplier": 2 } },
  "nodes": [
    { "key": "start", "type": "start", "config": {} },
    { "key": "draft", "type": "llm", "config": {
        "provider": "openai-compatible", "model": "deepseek-chat",
        "promptTemplate": "请根据问题起草答案要点：{{ vars.question }}",
        "outputMode": "text", "maxTokens": 1024 } },
    { "key": "enrich", "type": "retrieval", "config": {
        "knowledgeBaseIds": ["kb-demo"],
        "queryTemplate": "{{ vars.question }}", "topK": 5, "minScore": 0 } },
    { "key": "final", "type": "llm", "config": {
        "provider": "openai-compatible", "model": "deepseek-chat",
        "promptTemplate": "结合要点与检索给出最终回答。\n要点：{{ nodes.draft.output.text }}\n检索：{{ nodes.enrich.output.result }}\n问题：{{ vars.question }}",
        "outputMode": "text", "maxTokens": 1200 } },
    { "key": "end", "type": "end", "config": {
        "resultMapping": { "answer": "{{ nodes.final.output.text }}" } } }
  ],
  "edges": [
    { "from": "start", "to": "draft" },
    { "from": "start", "to": "enrich" },
    { "from": "draft", "to": "final" },
    { "from": "enrich", "to": "final" },
    { "from": "final", "to": "end" }
  ]
}
```

---

## 7. 节点输出契约（重放与下游引用的依据）

| 类型 | output 结构 | 可重试 |
|---|---|---|
| start | `{ variables }`（校验+默认值合并后） | — |
| end | `{ result }` | — |
| llm | `{ text, parsed?, tokenUsage{prompt,completion,total}, model, latencyMs }` | 瞬时 |
| retrieval | `{ chunks[{id,score,snippet,citation}], query, tookMs }` | 瞬时 |
| tool | `{ result, callId, latencyMs }` 或 `{ error }` | 视 connector.idempotent |
| condition | `{ matchedBranch, evaluated[] }` | — |
| loop | `{ items[], completed, limitExceeded? }` | 瞬时 |
| parallel | `{ branches{ <name>: output }, join }` | 瞬时 |
| transform | `{ variables }`（写入的 vars 子集） | — |
| subworkflow | `{ childExecutionId, result, tokenUsed }` | 瞬时 |
| human_gate | `{ decision: approved/rejected, resolvedBy, comment? }` | — |

---

## 8. 错误码目录

| 码 | HTTP | 场景 |
|---|---|---|
| `AGT-AUTH-001` | 401 | 未认证/令牌失效（不区分账号是否存在） |
| `AGT-RATE_LIMIT-001` | 429 | 网关限流 |
| `AGT-UPSTREAM-001` | 502 | engine 不可用（熔断开启） |
| `AGT-UPSTREAM-003` | 503 | Kill Switch 开启 |
| `WF-NOT_FOUND-001` | 404 | 资源不存在或跨租户（不区分） |
| `WF-FORBIDDEN-001` | 403 | 角色不足（审计记录尝试） |
| `WF-IMMUTABLE-001` | 409 | 修改已冻结版本 |
| `WF-STATE-001` | 409 | 非法状态转移 |
| `WF-POLICY-CLAMPED` | — | 仅审计：policy 被下调 |
| `DSL-VALIDATION-001` / `DSL-CYCLE-001` / `DSL-REF-001` / `DSL-ORPHAN-001` / `DSL-BRANCH-001` | 422 | DSL 校验族 |
| `EXEC-LOOP_LIMIT_EXCEEDED` | 200（执行内） | 循环超限，执行 FAILED |
| `EXEC-BUDGET_EXHAUSTED` | 200（执行内） | 预算硬停止 |
| `EXEC-DEADLINE_EXCEEDED` | 200（执行内） | deadline 到达 → TIMED_OUT |
| `PII-BLOCKED-001` | 200（执行内） | PII 第二层校验命中，节点永久失败 |
| `EXPR-EVAL-001` | 200（执行内） | 表达式求值失败（永久） |
| `LLM-RATE_LIMIT` / `TOOL-TIMEOUT` 等 | — | 瞬时，触发重试 |
| `LLM-NOT_CONFIGURED-001` | 200（执行内） | 真实 provider 未配置 `AF_LLM_BASE_URL` / `AF_LLM_API_KEY`（永久） |
| `LLM-AUTH-001` | 200（执行内） | 上游 401/403，密钥无效（永久，不重试） |
| `LLM-REQUEST-001` | 200（执行内） | 上游 400/404/422，请求或模型名非法（永久） |
| `LLM-RATE_LIMIT-001` | 200（执行内） | 上游 429（瞬时，重试） |
| `LLM-TIMEOUT-001` | 200（执行内） | 上游超时（瞬时，重试） |
| `LLM-UPSTREAM-001` | 200（执行内） | 上游 5xx / 网络异常（瞬时，重试） |
| `LLM-RESPONSE-001` | 200（执行内） | 上游响应体非法、空内容或超限（永久） |

---

## 9. 可观测性

- **trace**：W3C `traceparent` 前端 → gateway → workflow-service → engine → Provider；所有日志行含 `traceId`、`executionId`、`tenantId`（MDC / structlog contextvars）。
- **指标**（Prometheus）：`af_execution_total{status}`、`af_execution_duration_seconds`（直方图）、`af_node_failed_total{nodeType,errorCode}`、`af_event_append_latency_ms`、`af_replay_total`、`af_token_used_total{tenantId}`、`af_lease_contention_total`、`af_budget_stop_total`。
- **日志红线**：禁止出现原始 PII、提示词全文、用户问题原文、连接器密钥、DSN（CI grep + Code Review 双保险）。PII 日志只记类别与数量。

---

## 10. 完整文件清单（§15.1 交付物）

```
AgentFlow/
├── pom.xml                                   # 父 POM：模块聚合 + 依赖管理
├── README.md                                 # 架构图/功能矩阵/快速开始/环境变量/演示脚本/已知限制
├── docs/
│   ├── architecture.md                       # 本文件
│   ├── openapi/                              # 生成物提交入库（契约漂移 CI 基线）
│   │   ├── agent-gateway.yaml
│   │   ├── workflow-service.yaml
│   │   └── engine-api.yaml
│   └── adr/                                  # 后续重大决策
├── common/                                   # Java 共享模块
│   ├── pom.xml
│   └── src/main/java/com/agentflow/common/
│       ├── dto/          # ProblemDetail, Page<T>, GovernanceHeaders, PolicyDto...
│       ├── error/        # ErrorCode 枚举 + ProblemDetailFactory
│       ├── trace/        # TraceContext, MdcFilter, TraceRestTemplateInterceptor
│       ├── tenant/       # TenantContext, TenantHierarchyResolver
│       ├── pii/          # PiiMasker（pii-v2，与 Python 同规则）
│       ├── idempotency/  # IdempotencyService（Redis + MySQL 双写）
│       └── security/     # JwtService, RbacConstants
│   └── src/test/java/... # pii、幂等、JWT 单测
├── agent-gateway/
│   ├── pom.xml
│   └── src/main/java/com/agentflow/gateway/
│       ├── GatewayApplication.java
│       ├── config/       # RouteConfig（/api/v1/**、/engine/** 内部路由）、SecurityConfig、RedisConfig
│       ├── filter/       # AuthGlobalFilter, TenantResolveFilter, RateLimitFilter,
│       │                 # GovernanceFilter（预算钳制/KillSwitch）, TraceFilter
│       └── governance/   # PolicyClampService, KillSwitchService, EngineCircuitBreaker
│   └── src/test/java/... # 钳制、KillSwitch、404 语义、ArchUnit
├── workflow-service/
│   ├── pom.xml
│   └── src/main/java/com/agentflow/workflow/
│       ├── WorkflowServiceApplication.java
│       ├── controller/   # WorkflowController, VersionController, ExecutionController,
│       │                 # GateController(P3), AdminUsageController
│       ├── service/      # WorkflowService, VersionService(冻结/发布/回滚), ExecutionService,
│       │                 # TrafficRouter(P1), NotificationService(P3)
│       ├── projector/    # EventProjector(轮询 engine), ProjectorCursorStore, SseHub
│       ├── client/       # EngineGatewayClient（唯一出口，ArchUnit 保护）
│       ├── repo/         # MyBatis Mapper 接口 + resources/mapper/*.xml
│       └── config/       # MyBatis, Security, WebMvc
│   └── src/test/java/... # 版本不可变、幂等触发、投影、RBAC、ArchUnit
├── audit-service/
│   ├── pom.xml
│   └── src/main/java/com/agentflow/audit/
│       ├── consumer/     # AuditEventConsumer（RocketMQ，af_audit 账号写入）
│       ├── controller/   # AdminAuditController（cursor 分页）
│       └── service/
│   └── src/test/java/... # 只追加约束、查询
├── connector-service/                        # 连接器服务（P2，:8085）
│   ├── pom.xml
│   └── src/main/java/com/agentflow/connector/
│       ├── ConnectorServiceApplication.java
│       ├── config/       # JWT / 凭据密钥 / 沙箱策略
│       ├── crypto/       # CredentialCipher（AES-256-GCM，AAD=connectorId|tenantId）
│       ├── domain/       # ConnectorRepository（tool_connector / tool_credential）
│       ├── service/      # ConnectorService、ConnectorInvoker、SandboxPolicy、ConnectorAudit
│       ├── tenant/       # JdbcTenantTreePort（三级继承）
│       └── web/          # ConnectorController、InternalConnectorController
│   └── src/test/java/... # 加密篡改、SSRF、幂等缓存、跨租户 404
├── db/
│   ├── mysql/migration/  # V001__tenant_user_auth.sql ... V006__gate_eval.sql
│   ├── mysql/grants/     # least-privilege.sql（af_app / af_audit 最小授权）
│   └── postgres/migration/ # V001__event_log.sql
├── engine-api/
│   ├── pyproject.toml                          # ruff + pytest + 依赖锁定
│   └── app/
│       ├── main.py, config.py, errors.py
│       ├── api/            # internal.py, problems.py
│       ├── dsl/            # models.py, validate.py, compile.py
│       ├── engine/         # scheduler.py, executor.py, statemachine.py, state.py,
│       │                   # dispatcher.py, replay.py, requests.py, lease.py
│       ├── nodes/          # registry.py, base.py, subgraph.py, subworkflow_resolver.py
│       │                   # start/end/llm/tool/condition/loop/parallel/transform/
│       │                   # subworkflow/human_gate
│       ├── commands/       # base.py + providers/{connector_llm,openai_compatible,http_tool}.py
│       ├── events/         # store.py, log.py, types.py, publisher.py
│       ├── governance/     # budget.py, tokenmeter.py, pii.py
│       ├── expression/     # safeexpr.py, template.py
│       └── observability/  # trace.py, metrics.py, logging.py
│   └── tests/
│       ├── unit/           # dsl 校验、状态机全转移、重放、checkpoint、预算、
│       │                   # 表达式沙箱、PII、human_gate、故障注入
│       └── integration/    # testcontainers 自举 PG（不可用时整模块 skip）
├── frontend/
│   ├── package.json / vite.config.ts / tsconfig.json / eslint.config.js
│   └── src/
│       ├── api/            # http.ts（axios 拦截器）, index.ts（按域分组的客户端）, sse.ts
│       ├── dsl/            # types.ts, flow-convert.ts, validate.ts
│       ├── design/         # tokens.css, base.css
│       ├── stores/         # auth.ts
│       ├── views/          # LoginView, WorkflowListView, CanvasEditorView, VersionsView,
│       │                   # ExecutionDetailView, ExecutionListView, ConnectorsView,
│       │                   # ApprovalsView, EvalsView, AdminView
│       ├── components/     # canvas/{WorkflowNode,NodePalette,ConfigDrawer,VariablesPanel,
│       │                   #   DslSourcePanel}.vue
│       │                   # execution/{NodeTree,IoPanel,TracePanel}.vue
│       └── router/ main.ts App.vue
│   └── tests/              # auth-store / sse / flow-convert / dsl-validate / design-redlines
├── e2e/
│   ├── conftest.py                   # 登录与租户/用户 fixture、等待终态
│   ├── test_p0_happy_path.py         # 创建→草稿→发布→执行→事件树
│   ├── test_dsl_cycle_422.py
│   ├── test_cross_tenant_404.py
│   ├── test_idempotent_trigger.py    # 同键并发 → 1 执行
│   ├── test_condition_skip.py        # 条件分支 + 未命中显式 SKIPPED
│   ├── test_subworkflow.py           # 子工作流解析 + 输出映射
│   ├── test_canary_release.py        # 灰度发布
│   ├── test_tenant_inheritance.py    # 三级租户继承
│   ├── test_connector_flow.py        # 连接器 CRUD/授权/沙箱拒绝
│   ├── test_cost_center.py           # 成本账本累加与限额继承
│   ├── test_gate_approval.py         # human_gate 挂起→待办→裁决→续跑分流
│   └── test_evals.py                 # 评测跑分 + A/B 汇总
├── ops/
│   └── docker-compose.infra.yml     # mysql, postgres, redis, rocketmq（并含 grants 一次性容器）
├── scripts/
│   ├── start-dev.ps1 / start-dev.sh        # 一键启动（Windows / WSL2）
│   ├── stop-dev.ps1 / stop-dev.sh          # 停止（-Infra 连容器一起停）
│   ├── verify-all.ps1 / verify-all.sh      # 本地全量门禁（对齐 CI）
│   ├── seed-demo.sql                       # 演示租户/用户/示例工作流
│   ├── gen-seed.py                         # 生成密码哈希写入 seed
│   ├── dump-openapi.py                     # 导出引擎 OpenAPI 基线
│   └── gen-seed.py                         # 生成幂等种子 SQL
└── .github/workflows/
    ├── java-ci.yml         # mvn verify + ArchUnit + Checkstyle
    ├── python-ci.yml       # ruff check + ruff format --check + pytest
    ├── frontend-ci.yml     # eslint + vue-tsc + vitest + build
    └── contract-drift.yml  # 引擎 OpenAPI 生成物 vs 入库基线
```

---

## 11. 阶段映射（与 §11 计划对齐）

| 阶段 | 本文档支撑条目 |
|---|---|
| P0 | 模块骨架（common/gateway/workflow-service/audit-service）、V001–V004、start/llm/tool/end 节点、拓扑同步执行、事件追加、SSE、mock provider、3 条 E2E |
| P1 | 事件溯源全量（§5.3）、租约（§5.5）、Checkpoint、恢复扫描（D4）、condition/loop/parallel/transform/subworkflow、灰度（D8）、故障注入测试 |
| P2 | 三级租户继承、connector-service + V005、凭据加密、成本中心、预算硬停止对账（§5.6） |
| P3 | human_gate + V006、审批裁决回调（HTTP 直达 + outbox 重投）、评测集、A/B 对比 |

---

## 12. 开放点裁决（2026-11-16 已按推荐方案锁定）

1. **mock 连接器内置 engine-api**（P0–P1），P2 建 connector-service 承接真实工具。理由：遵守 §11 P0 模块清单；内置 mock 无凭据无外网，不触碰信任边界。
2. **沿用本人既有的前后端分层约定**（DocQA 工程实践）落到 AgentFlow 包结构，不做跨仓库引用。
3. **NodeCompleted 存完整脱敏输出**，其余事件存摘要+digest；溢出阈值 256KB 转 node_io。
4. **SSE = Redis pub/sub + afterSeq 追平**；RocketMQ 仅审计兜底与 P3 审批回调。
5. **E2E = API 级 pytest 打 gateway**（`e2e/`）；前端交互用 Vitest，Playwright 推迟 P3。
6. **真实 LLM 先直连、后补模型连接**（2026-11-18）：最初 `LlmExecutor` 直接新增 `openai-compatible` 实现、密钥只经 `AF_LLM_*` 注入，不引入新的服务间信任边界。随后补了 `connectionId` 路径——把端点与密钥托管进 connector-service，节点只持连接 id。理由是「一台端点一套密钥」无法支撑多空间、多厂商复用，且换密钥要重启引擎；复用既有 connector 的密文存储与沙箱，比新造一套 provider 表更省且口径一致。进程级 `AF_LLM_*` 保留为本地快速试，两条路径共用同一份请求构造与响应解析（`build_chat_payload` / `extract_text`），避免语义漂移。设计细节见 §5.7。

---

## 13. 实现期对齐记录（2026-11-16，P0 落地时与本文档的偏差修正）

1. **错误码新增**（§8 补充）：`EXEC-INPUT-001`（start 入参校验失败，永久）、`EXEC-MAX_STEPS_EXCEEDED`、`CONN-FORBIDDEN-001`（连接器未授权）、`CONN-NOT_FOUND-001`、`LLM-PROVIDER-001`、`LLM-UPSTREAM-001`、`NODE-TIMEOUT`（瞬时）、`NODE-INTERNAL-001`（永久）、`EVT-CONCURRENCY-001`、`TOOL-OP-001`；真实模型 provider（§5.7）另增 `LLM-NOT_CONFIGURED-001`、`LLM-AUTH-001`、`LLM-REQUEST-001`、`LLM-RATE_LIMIT-001`、`LLM-TIMEOUT-001`、`LLM-RESPONSE-001`。
2. **成功响应信封**：外部 API 成功响应统一 `ApiResponse{code:"OK", msg, data, traceId}`（复用 DocQA 惯例）；错误一律 ProblemDetail。§7 的响应体示例均视为 `data` 字段内容。
3. **RocketMQ 主题名**：`af-audit-event`（RocketMQ 主题不允许 `.`）。正文他处的 `af.audit.events` 以此为准。
4. **审计只追加的第三重保证**：除 DB 授权外，V004 迁移内置 BEFORE UPDATE/DELETE 触发器（`AUD-APPENDONLY-001`）；audit-service 的 ArchUnit 测试禁止 repo 包依赖 MyBatis `@Update`/`@Delete`。
5. **PG 新增 `execution_spec` 表**（V001）：派发请求快照（含不可变 DSL），使引擎崩溃恢复自足（D4），不读业务库。
6. **`workflow_definition` 增加 `seq BIGINT AUTO_INCREMENT UNIQUE`**：游标分页（cursor = base64(seq)）。
7. **`app_user` 增加 `platform_role ENUM('USER','ADMIN')`**：平台级 /admin/** 判定；租户角色仍在 `tenant_member`。
8. **`refresh_token` 用 `status ENUM('ACTIVE','USED','REVOKED')`** 替代 revoked_at（重用检测语义），与 DocQA 轮换族一致。
9. **前端 API 客户端**：手写 axios 模块 + `tests/` 契约与设计红线守卫测试（DocQA 同款）；OpenAPI 生成物限引擎侧（`docs/openapi/engine-api.json`，contract-drift CI）。Java 侧 springdoc 生成物入库移到 P1。
10. **P0 已提前实现的 P1 条目**：事件溯源全量（事件即真相从第一天落地）、租约、Checkpoint、崩溃恢复扫描（启动 + 周期扫描兜底）。故障注入测试矩阵仍在 P1。

---

## 14. P2 落地对齐记录（2026-11-17）

1. **三级租户继承语义**：`TenantHierarchyResolver` 沿 `parent_id` 向上解析（≤8 层 + 环检测）。
   直接成员角色原样生效；祖先成员的 **OWNER 降级为 ADMIN**（组织所有者不应在子空间同样拥有
   「所有者」语义）；无成员关系时平台 ADMIN 兜底 ADMIN。祖先节点缺失或成环只截断链，
   不把合法空间判成 404；目标空间本身缺失才 404。
2. **连接器凭据格式**：`12B IV || ciphertext || 16B tag`，AAD = `connectorId|tenantId`，
   使密文无法跨连接器/租户搬运；`key_id` = 主密钥 SHA-256 前 12 hex，为轮换留版本位。
   OAuth `state` 用同一主密钥派生 HMAC-SHA256、截断 16 字节、10 分钟过期。
3. **沙箱边界**：只允许 https（显式开关下允许 loopback + http）；host 必须命中
   `config.allowedHosts`（`*.` 通配仅匹配真子域）；DNS 解析结果拒绝环回/私网/链路本地/
   组播/CGNAT；不跟随重定向（防白名单绕过）；响应体上限 256KB。
4. **连接器调用路径**：`engine → gateway /internal/connectors/{id}/invoke → connector-service`，
   与 `subworkflow` 走同一条「Python 只经 gateway」的边界；`callId` 在连接器服务侧再做一次
   Redis 幂等缓存（24h），崩溃重发返回首次结果。
5. **授权清单来源**：workflow-service 触发时取「connector-service 目录中该空间可见且启用的
   连接器 id」；连接器服务不可用时集合为空，真实连接器节点会在授权校验处
   `CONN-FORBIDDEN-001`（不再有内置兜底清单）。
6. **成本中心**：`budget_ledger` 由投影器在 `NodeCompleted` 时累加（与游标同事务，重放不重复）；
   `/admin/usage` 额外返回 `tenants`（沿祖先链取最小限额，并标出是谁卡住的，见 §26）。**预占泄漏**由
   `BudgetLedger.reconcile` 在恢复与 checkpoint 时按事件流 `tokenUsed` 校准。
7. **V005 补充约束**：`tool_credential.connector_id` 唯一（一个连接器一份凭据），
   便于 upsert；其余列与 §3.1 草案一致。

---

## 15. P3 落地对齐记录（2026-11-17）

1. **挂起不是失败**：`human_gate` 节点只由执行器抛 `GateSuspendedSignal`（刻意**不**继承
   `EngineError`，避免被瞬时/永久错误分类和重试逻辑接住），NodeRunner 落
   `NodeSuspended` + `GateSuspended` 后原样上抛，调度器返回 `SUSPENDED` 而不落执行级终态。
   `workflow_execution.status` 因此新增 `SUSPENDED`（V006）。
2. **挂起必然落 checkpoint**：变量只活在内存与快照里，挂起时强制写一次
   `CheckpointCreated`（变量快照是续跑的唯一来源）。这条与「每 N 事件一次」的常规节奏无关，
   是挂起路径的硬要求：否则裁决续跑会静默用回初始变量。
3. **`NodeSuspended` 携带 `attempt`**；`GateSuspended` 是业务载荷（审批人规则/提示词/超时），
   **不带** attempt，因此节点索引只由 `NodeSuspended` 投影。早期两者都投影会让
   `node_execution` 多出一条 `attempt=0` 的幽灵行，真正的那次尝试永远停在 `RUNNING`。
4. **超时裁决落在引擎侧**：`Dispatcher.sweep_gates()` 每 `AF_GATE_SWEEP_INTERVAL_S`（默认 15s）
   扫 `GateSuspended` 之后同节点无 `GateResolved` 的挂起执行，到点按 `onTimeout`
   （`REJECT` / `DEFAULT_BRANCH`）自动裁决。理由：事件库是唯一真相，进程重启后超时仍然生效；
   控制器只做「人点了通过/驳回」这一件事。
5. **裁决幂等三态**：`resolve_gate` 返回 `ALREADY_RESOLVED`（已裁决，返回原分支）/
   `ALREADY_TERMINAL`（执行已终态，`WF_STATE_001`）/ `BUSY`（拉租约失败，409
   `EXEC-GATE_BUSY-001`）。租约 owner 每次带随机后缀（`:gate:` / `:run:`），
   避免「挂起释放租约」删掉「裁决续跑」刚拿到的那把。
6. **审批人展开**：`ROLE` 沿租户祖先链按角色阈值取成员（≥ADMIN 时并入平台
   ADMIN）；`USERS` 用 `TenantHierarchyResolver` 过滤掉不可见成员；`EXPRESSION` 逐候选求值
   （见 §16.2）；展开为空回退平台管理员。
   快照在挂起瞬间冻结进 `gate_task.approvers`（JSON），后续成员变动不影响已发出的待办。
7. **裁决回调的 outbox + 广播**：控制器先 `POST /internal/executions/{id}/gates/{nodeKey}/resolve`
   到引擎；引擎不可达（`AGT_UPSTREAM_001`）时写 `gate_resolve_outbox` 并返回 `DEFERRED`，
   `GateResolveRelay`（默认 5s）退避重投 `min(10s×n, 300s)`，20 次置 `FAILED`。
   引擎侧 404/409 映射为 `WF_STATE_001`（状态冲突，不重投）。另向 `af-gate-decision`
   广播一条裁决消息供引擎并行消费（§16.3）。
8. **通知幂等**：`notification.id` 由 `gate|executionId|nodeKey|userId` 派生确定性 UUID，
   投影重放不会给同一个人发第二条待办。
9. **评测**：一次评测 = 对每个启用用例触发一次真实执行（幂等键 `eval:{runId}:{caseId}`），
   轮询器在用例执行落终态后取 `result` 做断言；断言四种模式 EXACT / CONTAINS / EXISTS
   （点号路径）/ NUMERIC（min/max），`12` 与 `12.0` 归一为等价。
10. **A/B 结论口径**：通过率优先（差 ≤0.5pp 视为持平）→ 平均耗时（≤50ms 持平）→
    平均 token；两者都持平返回 `TIE`，不编造胜负。
11. **子图内禁止 `human_gate`**（`SUBGRAPH-GATE-001` / DSL 校验期即拒）：loop/parallel 的
    并发迭代与 subworkflow 的嵌套执行都假设「一个节点一次事件序列」，挂起会破坏该假设。
12. **dev 限流放宽**：`start-dev` 通过 `AF_RATE_LIMIT_TRIGGER_MAX=300` 覆盖网关默认
    `20 次/60s/用户`（全量 E2E 一轮触发数十次执行）；产品默认值不变。
13. **两个 P3 期间的回归缺陷（已修，附单测）**：
    - `checkpoint_payload` 直接引用 `state.variables`，事件折叠时 `clear()` 把快照一起清空 →
      写完 checkpoint 变量即丢（`tests/unit/test_checkpoint.py`）。
    - `NodeSuspended` 不带 `attempt` → 节点索引写出幽灵 attempt=0 行（见第 3 条）。

---

## 16. 遗留四项收口（2026-11-19）

上一轮把「能力已支持、画布够不着」的缺口补完了，但仍留下四处在验收口径上站不住的
硬伤：审计靠 MQ 且 MQ 一挂就只剩进程日志、引擎只收 HTTP 回调、`EXPRESSION` 审批人在
引擎侧被当成非法类型、子图只能手写 JSON。这一节记录四者的落地方式与取舍。

### 16.1 审计改为 outbox + relay（`db/mysql/migration/V007`）

原先 `AuditPublisher` 直接 `send` 到 RocketMQ，`AF_MQ_ENABLED=false` 时就退化成
「每次 emit 一条 WARN」——等于没有留痕。现在改成与控制面其他写路径同源的
transactional outbox：

1. `OutboxAuditPublisher` 在**业务同库同事务**里 `INSERT audit_outbox`，不做任何网络调用；
   写 outbox 本身失败时才尝试一次直达，两者都失败才 `log.error("audit LOST …")`。
2. `AuditOutboxRelay`（默认 2s 一轮）取 `due` 批次投递：成功 `markSent`，失败
   `markRetry(attempts+1, delay = min(5×attempts, 60))`。
3. **审计不允许“放弃投递”**——这点与 `gate_resolve_outbox` 不同：后者 20 次置 `FAILED`
   可以接受（执行本身另有事件真相），审计放弃了就真的没有记录。所以 `status` 只有
   `PENDING` / `SENT`，重投次数没有上限，只有退避上限。
4. 连续失败达 `AF_AUDIT_DEGRADE_AFTER_ATTEMPTS`（默认 3）次后，出口切到
   `direct:audit_event`（用只读 `af_audit` 账号直写库，`DuplicateKeyException` 视为已送达）；
   `AF_AUDIT_SINK=direct` 可显式走这条，`mq` 可强制不许降级。降级后的消息仍会 `markSent`
   关单，避免同一条事件在两条出口上反复横跳。
5. `V007` 的 `audit_outbox` 与 `audit_event` 共用「只追加」纪律：relay 只做
   `PENDING→SENT` 的状态迁移与 7 天后的物理清理，不改写载荷。
6. 积压必须可见：`GET /api/v1/admin/audit-outbox`（平台 ADMIN）回 `sink / degradedSink /
   pending / retrying / oldestPendingAt / oldestPendingAgeSeconds`。outbox 的前提是
   「积压能被看见」，否则它只是把丢失变成静默延迟。

`RocketMqAuditPublisher` / `NoopAuditPublisher` 随之删除——它们编码的是「要么发出去、
要么只记一行日志」的旧假设。

### 16.2 `EXPRESSION` 审批人：一份文法，三处实现

表达式要有意义，必须在「发布」「运行」「编辑」三个时刻给出一致的判定，否则会出现
「画布放行、发布拒绝」或「发布过了、运行时谁都不能批」。为此定了一个**受限子集**并三处同构：

| 位置 | 实现 | 时机 |
|---|---|---|
| 引擎（Python） | `app/dsl/validate.py` 用 `ast.parse` + 白名单递归，拒函数调用/下标/未知属性 | 发布期（`DSL-GATE-EXPR` 类错误） |
| 控制面（Java） | `gate/GateExpression`：手写词法+递归下降，`validate` / `evaluate` 两入口 | 运行期展开审批人 |
| 画布（TS） | `dsl/approver-expr.ts`：`approverExpressionError()` 即时体检 | 编辑期 |

文法：属性 `user.id` / `user.role` / `user.rank` / `user.platformAdmin` / `space.id`；
比较 `== != < <= > >=`；成员 `in` / `not in`；布尔 `and` / `or` / `not`（支持括号）；
字面量 字符串 / 整数 / 布尔 / `null` / 列表。**没有函数调用、没有下标、没有属性链**——
`len(...)`、`.startswith(...)`、`users[0]` 一律在发布期被拒（`ast` 白名单里根本没有
`Call` / `Subscript`），避免把「表达式」做成一个可执行为任意逻辑的沙箱逃逸面。

求值不按角色粗筛：`GateApprovers.resolveByExpression` 对空间链上**全部成员**逐个判定，
再单独过一遍平台管理员（他们未必出现在成员表里）。表达式为空或非法 → 打 ERROR 并返回
空集，即 **fail-closed**：宁可「无人可批、超时按 `onTimeout` 收口」，也不放行。

### 16.3 引擎真正消费 MQ（`engine-api/app/messaging/`）

审批回调原本只有 HTTP 一条路，`gate_resolve_outbox` 负责补偿。现在加了第二条通道：
控制面裁决成功后向 `af-gate-decision` 发一条
`{executionId, nodeKey, decision, resolvedBy, comment, occurredAt}`（key =
`executionId:nodeKey`），引擎侧 `GateDecisionConsumer` 并行消费，重复裁决由引擎既有的
幂等三态吸收。

不引官方 Python 客户端：`rocketmq` / `rocketmq-client-python` 都是 C++ 客户端的 ctypes
绑定，要本机有 `librocketmq`，Windows 与精简 CI 装不上。于是按 5.x remoting 协议
（帧 = `totalLen + headerLen + headerJson + body`，V1 消息定长头 88 字节 +
`body/topic/props`，properties 用 `\x01` / `\x02` 分隔）用标准库自己实现，`remoting.py`
只做编解码、`client.py` 做连接与拉取。踩过的三个坑，都留在代码注释里：

1. **不发心跳**。5.x `ClientManageProcessor` 只从 body 解析 `consumerData`，放 `extFields`
   会在注册循环里 NPE；而且心跳会**多回一帧**（`40` 紧跟 `0`），把一问一答的连接错位。
2. **不挂起长轮询**。`_PULL_FLAG` 只留 `SUBSCRIPTION`、`suspendTimeoutMillis=0`——
   5.x 的挂起时长由 broker 侧 `brokerSuspendMaxTimeMillis`（15s）决定，客户端传的值
   被忽略，必然先踩到 socket 超时。改成短轮询 + 200ms 空闲间隔。
3. **每次 `connect()` 先 `close()`**。半连接（地址已记、连接对象为 `None`）会让重连被
   跳过，表现为「一直超时但看不出没连上」。

偏移用 `GET_MAX_OFFSET`（只取新消息，不重放历史）；`PULL_OFFSET_MOVED` 接受 broker
建议值；topic 尚未创建（`code=17`）降级为 INFO 并重试，同时在 `start-dev` / `e2e`
流水线里预建 `af-audit-event` 与 `af-gate-decision`。消费状态见 `GET /internal/mq/status`
（`consumed / applied / duplicate / stale / invalid / failed / lastError`）。三类非故障
结果要分清：`applied` 是本通道真的推进了状态机，`duplicate` 是节点已裁决，`stale` 是执行
已收口、消息迟到。HTTP 是快路径，MQ 大多数时候在后两者里，把它们算成 `failed` 只会让
告警长期变红并掩盖真故障。`BUSY`（租约被 HTTP 侧占着）会先本地重试 3 次再计数。

### 16.4 子图画布（`frontend/src/dsl/subgraph.ts` + `CanvasEditorView`）

`loop.body` / `parallel.branches` 此前只能在抽屉里手写 JSON，等于把最容易写错的
（层内 key 唯一性、不能有 `start`、节点数下限）交给用户。现在：

1. **一份真源**：`rootDsl` 是整份 DSL，`path: SubgraphRef[]` 表示当前层
   （`{nodeKey, branch?}`）。`readSubgraph` / `writeSubgraph` 都是不可变的，未命中
   返回同一对象（避免 Vue 无谓的深层 diff）。`toDsl()` 把当前层经 `writeSubgraphPath`
   合并回根，变量与 defaults 始终取根层——子图不拥有自己的变量表。
2. **层内编辑**：子图内 `start` 置灰（入口由上层节点决定，子图没有 start）；层内 key 只需
   层内唯一。**只有整图**必须留一个 `end` 且删掉最后一个会被拒绝——子图的出口由上层
   `loop` / `parallel` 节点收口，引擎同样不要求子图内有 `end`（见 §17.2）。
3. **校验能下钻**：`validateGraph(nodes, edges, scope)` 递归 `validateNested`，问题 key
   带层前缀（`loop_1.body#body_end`），画布与抽屉都能把错误挂回具体那一层。深度上限 4。
4. **JSON 不删**：抽屉里的文本域改成「高级：直接编辑 …JSON」，无解析错误时从配置实时
   序列化，和表单是同一份数据的两个视图。

## 17. 交付前对齐记录（2026-11-20，v0.4.0）

这一版没有新增大模块，处理的是「一处放行、另一处拒绝」「界面显示的和投影里存的不一致」
这类跨层错位。它们不体现在功能矩阵里，但都足以让一条合法流程跑不通。

### 17.1 表达式字面量：三处同文法补齐

§16.2 的文法表里漏了字面量。`ast.parse("vars.urgent == true")` 把 `true` 解析成
`ast.Name`，发布期白名单判「未知标识符」，而画布编辑器与 Java `GateExpression` 都放行；
引擎侧的 `simpleeval` 又没有 `true/false/null` 别名，同一句在执行期报
`EXPR-EVAL-001`。现在：

- 发布期 `app/dsl/validate.py` 认 `true/false/null/none` 四个 `ast.Name`；
- 运行期 `app/expression/safeexpr.py` 把 `_LITERAL_ALIASES` 并入 `evaluate` 的 names；
- 编辑期 `approver-expr.ts` 的关键字（`and/or/not/in` 与字面量）大小写不敏感，
  标识符（`user` / `space` / 属性名）保持区分大小写，与 Java 递归下降一致。

### 17.2 子图规则与引擎严格对齐

引擎按 `WorkflowDsl` 解析 `loop.body` / `parallel.branches`，`SubGraph.nodes` 有
`minItems: 2`。此前画布强制子图必须有 `end`，把合法图报成校验错误；单节点子图却能通过
发布、一执行就 `PARALLEL-BRANCH-FAILED`。现在统一为：**整图**必须留一个 `end`，
**子图**不放 `start`、不要求也不依赖 `end`（出口由上层节点收口）、至少 2 个节点。
`subgraphRemovalError(..., isRoot)` 只对整图做末位 `end` 保护。

### 17.3 执行详情：实时事件不能覆盖 REST 快照

`GET /executions/{id}` 返回的是投影后的最新状态，但详情页的 SSE 原来从 `afterSeq=0`
连上去，会先回放整条事件流：旧的 `ExecutionStarted` 把 `SUSPENDED` 刷回 `RUNNING`，
不带 `attempt` 的 `GateSuspended` 又给同一节点补出一条 `attempt=0` 的重复行。修复：

- 连接前用 `executionApi.events(id, lastSeq, 500).lastSeq` 对齐游标，SSE 只收增量；
  取不到游标就退回回放，下面的状态映射兜底。
- 执行级状态补齐 `GateSuspended → SUSPENDED` / `GateResolved → RUNNING`，
  `ExecutionStarted` 不再覆盖挂起态或终态。
- 节点事件按 `nodeKey + attempt` 找行；payload 里没有 `attempt` 时按 `nodeKey` 找最后一行，
  不再凭空 `push` 一条。终态与挂起态不会被历史事件倒退，但裁决续跑后的 `NodeStarted`
  仍能把 `SUSPENDED` 推回 `RUNNING`。
- `NodeFailed.message` 经 `V008` 落到 `node_execution.error_message`，执行详情的 I/O 面板
  直接展示错误码 + 原因，不必回去翻事件流。

### 17.4 画布首屏与变量面板

DSL 存的是绝对坐标，宽流程的首屏会只看到前几个节点、末尾的 `end` 被裁在视口外。
现在 `VueFlow` 开 `fitViewOnInit`，并在 `nodesInitialized` / 切子图后重新
`fitView({ padding: 0.15 })`。变量面板的折叠初值改为跟着变量数走（画布先挂载、DSL 后到），
用户手动点过之后不再被自动折叠。

### 17.5 内置示例二：客户工单分诊

`scripts/gen-seed.py` 改成按清单生成多个示例，`wf-demo-triage` 在一条链路上跑齐
`condition` / `parallel` / `human_gate(EXPRESSION)` / `loop`，并有 approved / rejected 双出口。
两条例外都要注意：子图至少 2 节点；`end.resultMapping` 只能引用**两条分支都会写入**的变量——
被 `SKIPPED` 的节点没有 `output`，直接引用会在收口时 `EXPR-EVAL-001`。所以两条分支各自把
结果写进 `vars.outcome`，`end` 只取这一个变量。canonical 串与 checksum 仍由生成脚本计算，
与服务端落库口径一致。

## 18. 前端呈现层改造（2026-11-20，ComfyUI 式画布）

### 18.1 边界

这一轮只动 `frontend/`：DSL schema、引擎、控制面 API、数据库、投影、发布期校验与
既有 34 个全栈 e2e 全部未改。新增能力都是「加法」，原能力一个不删：

- `ConfigDrawer.vue`（全字段）与 `DslSourcePanel.vue`（JSON）原样保留，收进卡片
  「展开完整配置」与工具条「DSL」两个高级入口，字段逻辑一行未重写。
- `NodeTree.vue` 扁平列表原样保留，执行详情新增「节点列表」标签切过去。
- 子图画布的面包屑逐层进出不变；折行只影响排布，不改 `body` / `branches` 语义。

### 18.2 节点卡片

`dsl/node-card.ts` 把卡片呈现规则从视图里抽出来：`nodeCategory` 三类
（执行 / 控制 / 边界）、`categoryColor` 只返回设计令牌、`inlineFields` 每类挑 1~2 个
顶层字段、`nodeSummary` 给出子图规模与审批人规则、`statusColor` 守三色语义。
`WorkflowNode.vue` 只负责渲染；内联编辑通过 `update-config` 抛一个 patch，
`CanvasEditorView.patchNodeConfig` 只合并顶层字段，不重建节点、不碰 `cases` / `mappings`
这类嵌套数组（嵌套结构仍只读展示）。表单元素一律加 `nodrag` 与
`mousedown` / `pointerdown` / `click` / `keydown` 的 `stop`，避免与 Vue Flow 拖拽、
画布快捷键冲突。

### 18.3 交互与校验前置

`dsl/connection.ts` 的 `connectionProblem()` 把发布期已有的连线规则前置到落线瞬间；
`dsl/auto-layout.ts` 按拓扑分层并在超过 4 层时折行（`MAX_COLS=4`），只用于展示与
「整理布局」，不改持久化的 `position`。`NodeSearch.vue` 支持中文名模糊匹配，
`addFromSearch` 在落位后按需补一条连线；三个模板都在单测里过一遍
`validateDslLocal`，保证拖进来就能发布。

### 18.4 执行态复用同一张图

执行详情从 `workflowApi.detail(definitionId).versions` 里按 `execution.versionId`
取**冻结版本**的 `dslJson`，不用当前草稿，否则节点与执行记录对不上。
`dsl/exec-status.ts` 的 `aggregateStatus` 把 `gather#kb#kb_hit` / `polish#0#polish_sign`
这类带层级的执行记录按最严重状态聚合到根层卡片，避免分支失败被整片 `SUCCEEDED` 盖住。
`ExecutionGraph.vue` 对冻结 DSL 做一次纯展示用的折行 `autoLayout`，再交给只读
`VueFlow`；`fitView` 在 `nodesInitialized` 与节点数变化后各触发一次（节点数据是异步
到达的，初始化那次可能量不到尺寸）。SSE 合并逻辑未改，`detail.nodes` 变化时两个视图
同时刷新；DSL 取不到时自动回退列表视图并给出说明。

### 18.5 设计红线与验收

`design/tokens.css` 只新增一个分类色 `--af-node-control`；状态色仍严格是
强调（进行/挂起）、危险（失败）、中性（完成）三种，`design-redlines.spec.ts`
继续守「原始色值只在 tokens/base」「全仓无绿色」。新增 44 个前端单测覆盖
节点分类与状态色、内联编辑、连接校验、折行布局、节点搜索、模板合法性、
执行图状态聚合；前端 168 passed，全量门禁 ALL GATES GREEN，全栈 e2e 34 passed。

## 19. 模型清单与画布一键接入（2026-11-20）

### 19.1 问题与边界

§18 把节点配置搬到了图上，但 LLM 节点的「模型」仍是一个裸文本框：模板默认值
`mock-large` 看起来像没配好，而接真实模型要先去「连接器」页手填 `baseUrl`、
`allowedHosts`、`auth`、`operations`，再回画布选「模型来源」，路径过长。

本次只做**呈现层加法**，硬约束与 §18 一致：

- 接入路径只在前端与连接器页增加了编排逻辑，引擎与控制面的接口契约保持不变。
- 安全边界不变：`LlmConfig` 仍是 `extra="forbid"`，`baseUrl` / `apiKey` **绝不进 DSL**；
  端点与密钥只在 connector-service 解密后注入出网请求头，画布只持 `connectionId`。
- 高级模式全保留：`ConfigDrawer` 的模型来源 / provider / model、`ConnectorsView`、
  DSL / JSON 视图、进程级 `AF_LLM_*` 三条原有路径一个不少。

### 19.2 预设清单是资料，不是配置

`dsl/model-presets.ts` 存的是各家**公开可查的 OpenAI 兼容端点**与常见模型别名
（混元 Hy4 / DeepSeek / 智谱 GLM / Kimi / 通义千问 / MiniMax / 豆包 / 硅基流动 /
Step / OpenAI / 本地 Ollama），附厂商、标签、Key 申请地址、易错说明。它不含任何密钥，
也不参与 DSL 校验：端点与模型名在向导里都可编辑，服务商控制台才是最终依据。
`needsServerOptIn` 标注本地 Ollama 这类 `http + loopback` 预设需要
`AF_SANDBOX_ALLOW_LOOPBACK` / `AF_SANDBOX_ALLOW_HTTP`，避免把沙箱拦截误判成故障。

### 19.3 两步交互与连接编排

`ModelHub.vue` 管两步：`ModelPicker.vue`（分组平铺列表、中文搜索、厂商标识、标签、
内置演示 / 自定义 / 管理连接三个入口）→ `ModelConnectDialog.vue`（名称、Base URL、
模型名、API Key、高级选项）。卡片上的「模型」字段由 `InlineField.kind='picker'`
渲染成按钮，`displayModelName()` 把 `mock-*` 显示成「内置演示模型 · 离线可跑」，
`nodeSummary()` 补一行接入提示；执行态只读时仍退化为纯文本。

`dsl/model-connection.ts` 串起现有 API：

```
create(LLM/API_KEY, config) → authorize(id, secret) → probeModels(id) → update(id, config)
```

`probeModels` 在服务端解密凭据后真实请求 `GET /models`，因此一次操作同时验证
网络可达、沙箱放行、凭据正确与 OpenAI 兼容格式。验证通过后把 `connectionId` / `model`
写回节点 `config`，节点立即可执行；失败则 `safeRemove()` 删掉临时连接并返回
`probeErrorMessage()` 的人话原因，列表里不留脏记录。上游不提供 `/models` 时可用
`allowWithoutProbe` 降级按预填模型名保存，本地无 Key 端点走 `authType=NONE` 并跳过探测。

`buildLlmConfig()` / `hostOf()` / `mergeModels()` / `probeErrorMessage()` 同时被
`ConnectorsView.vue` 引用，两条入口共用一份 config 构造与错误口径，不再漂移。
§6.2 的 llm JSON Schema 同步补上 `connectionId`（此前只有 Pydantic `LlmConfig`
与前端运行时在写，文档与 `additionalProperties:false` 不一致）。

### 19.4 验收

新增 `model-presets` / `model-connection` / `model-picker` / `model-connect-dialog`
四组单测，并更新节点卡片、配置抽屉的既有断言；前端 204 passed，typecheck / lint /
build / design-redlines 全绿。实机验证覆盖：模型清单渲染、预设预填、无效 Key 出网
返回 401 后向导就地显示「检查 Base URL 路径与 API Key」并保持可修改，以及演示模型
卡片提示。截图落在 `docs/images/model-picker.png`、`model-connect.png`、
`model-connect-error.png`，`canvas.png` 同步刷新。

## 20. 文档检索与「运行即所见」（2026-11-20）

§19 完成了模型接入向导，但接入只改草稿、触发只读版本快照，两条路径的 DSL 并不一致；
内置示例也还停留在离线演示配置。本节记录两处补齐：知识库与 `retrieval` 执行器，
以及「运行」按画布当前草稿生成版本快照。

引擎的 `openai-compatible` provider 用 `httpx` 请求 `POST /chat/completions`，
`connectionId` 路径经网关到 connector-service，由后者解密注入凭据并做出网校验。
内置示例与模板现在默认指向这条链路，检索节点读用户导入的文档；未注入
`AF_LLM_*` 时节点返回 `LLM-NOT_CONFIGURED-001`，不再回落到确定性示例文本。
离线 provider 已从 `app/` 移除，离线夹具移到 `tests/support/`，产品装配不再注册；历史 DSL 里的 `provider: mock` 会在 schema 校验阶段给出「请设置 connectionId 或改用进程端点」。

### 20.1 V009：知识库 / 文档 / 切片

检索不引入 pgvector 或外部 RAG 服务，打分在本地用 BM25 完成（见 §20.2）：
文档内容不出控制面，也不新增基础设施依赖。`db/mysql/migration/V009__knowledge.sql`
建三张带 `tenant_id` 的表：

```
knowledge_base      (id, tenant_id, name, description, created_by, created_at, deleted_at)
knowledge_document  (id, tenant_id, kb_id, title, source, char_count, chunk_count, ..., deleted_at)
knowledge_chunk     (id, tenant_id, kb_id, document_id, ordinal, document_title, content, token_count)
```

写入时切片（`KnowledgeService.split`）：~420 字符窗口，优先在句末标点/换行断开，
段间保留 60 字符重叠，纯确定性算法保证同一文档每次得到相同切片。文档/知识库软删时
**同步物理删除切片**，于是检索 SQL 不需要 JOIN 软删状态，过滤条件可完全下推到 SQL。
`KnowledgeMapper.BASE_SELECT` 把 `documentCount` / `chunkCount` 两个统计子查询与
其余 6 列合并成 8 列，与 `BaseRow` 的 8 个字段对齐——此前 `findBase` 只查 6 列，
MyBatis 构造器映射失败，知识库列表在实机返回 500；`KnowledgeServiceTest` 用列数契约
守住这处回归。`scripts/gen-seed.py` 同时写入 `kb-demo` 与三份售后文档切片，
种子里的两条示例升级为 v4 并引用该知识库，起服后无需手工导入即可检索到真实内容。

### 20.2 BM25 与信任边界

`KnowledgeService.searchForTenant` 在应用层做 BM25（k1=1.5, b=0.75），分数经
`1 - exp(-raw / 3)` 有界归一化到 (0,1)，`minScore` 才有稳定含义。词项
（`tokenize`）：ASCII 字母数字按整词小写；CJK 同时产单字与相邻二元组，中文查询
无需分词器也能召回。候选集由 `listChunksForSearch` 以 `tenant_id + kb_id IN (...)`
下推并用 `LIMIT 20000` 硬上限，`topK` 上限 50。

不引入向量库 / embedding 服务：全部计算在本地 JDK 完成，文档内容不出控制面，
离线可跑。代价是词法检索而非语义召回，见 README「已知限制」。

两条入口共用 `searchForTenant`：用户侧 `POST /api/v1/knowledge/search`（画布配置前
自测）与内部 `POST /internal/knowledge/search`（引擎检索节点）。内部端点只认
`X-Internal-Token`，`tenantId` 来自执行上下文而非请求方可信输入，
`distinctOwnedBases` 逐 kbId 调 `findBase` 复核归属，越权 id 静默忽略——既不返回
数据，也不报错暴露存在性。网关 `internal-dsl` 与 `workflow-service` 路由同时放行
`/internal/knowledge/**` 与 `/api/v1/knowledge/**`，`TenantFilter` 同步纳入。

### 20.3 retrieval 执行器

`engine-api/app/nodes/retrieval.py` 的 `RetrievalExecutor` 通过
`KnowledgeSearchClient`（`httpx.AsyncClient`）调用内部端点，错误按既有口径分类：
超时/网络/5xx → `TransientNodeError`（由 RetryPolicy 重试），401/403/404 与畸形响应
→ `PermanentNodeError`。Command 事件照旧发 `CommandIssued` / `CommandCompleted` /
`CommandFailed`，`callId` 仍由 `executionId/nodeKey/attempt` 确定性派生。
输出 `text` 是带 `《标题》 片段 N（相关度 …）` 出处的拼接上下文（上限 32KB），
`results` / `citations` 保留结构化命中，`result` 与 tool 节点同形，模板可直接沿用。
`build_executors` 新增 `knowledge_gateway=(gateway_base, internal_token)`；未配置时
节点明确报 `RETRIEVAL-NOT_CONFIGURED-001`，不静默回落到示例文本。
`Settings.supported_node_types` 正式加入 `retrieval`，DSL 校验不再拒绝。

前端：`KnowledgeView.vue` 建库/导入/试检索/一键生成工作流；`ConfigDrawer.vue`
为 retrieval 提供知识库多选、`queryTemplate` / `topK` / `minScore`；
`validate.ts` 要求至少选一个知识库；`NodePalette` / `node-card` / `flow-convert`
同步补齐类型描述、卡片字段与新建默认配置。

### 20.4 「运行」按草稿执行，而不是按已发布版本

`ExecutionService.trigger` 只认版本快照：不带 `versionId` 时走
`def.currentVersionId`（或灰度分流），而接入向导改的是当前编辑中的草稿，
两者不是同一份 DSL，因此刚接入的连接不会出现在执行里。

`CanvasEditorView.run()` 现在：保存当前草稿 → 取 `detail` 比对 checksum →
若已发布版本 checksum 与草稿一致则不带 `versionId`（保留灰度语义）；
否则复用历史中同 checksum 的版本，找不到才 `freeze` 一份快照，最后显式带
`versionId` 触发。因此：

- 跑的是画布所见，接入的模型连接/文档检索立刻生效；
- **不改已发布版本、不改灰度配置**，正式发布仍只由「冻结并发布」触发；
- 同内容草稿不重复冻结，避免版本表被每次运行刷满。

`inspectDemo` 在导入旧版演示 DSL 时给出提示；`applyConnectionToAllLlm` 把整层
llm 节点切到指定连接，`confirmTemplate` 用 `bindConnectionToLlmNodes` 在应用模板时
整层改写（含子图）。两条路径都要求连接带默认模型名，否则不改写。

### 20.5 验收

- Python：`test_retrieval.py`（真实切片、模板渲染、错误分类、客户端信封解析、
  阶段门放行）、`test_dsl_validate.py`（白名单摘除仍拒绝）；ruff + 308 passed。
- Java：`KnowledgeServiceTest`（切片/词项/BM25 排序/topK/minScore/租户隔离/
  列数契约）；`mvn verify` 全模块 BUILD SUCCESS。
- 前端：`demo-state.spec.ts`（演示态与整层改写含子图）、`knowledge-view.spec.ts`、
  `canvas-editor.spec.ts`（运行带 `versionId` 触发）；typecheck / lint /
  228 passed / build 全绿。
- E2E：`e2e/test_knowledge_retrieval.py` 覆盖建库→导入→检索→跨租户隔离→删除清切片，
  以及 retrieval 节点产出里出现导入原文且不含「测试语料片段」；`e2e/test_subworkflow.py`
  用自建子流程验证 `sub#run#` 前缀与 `outputMapping`，不把模型可用性耦合进来。先起全栈
  （`scripts/start-dev.*`）后，`AF_E2E_REAL_LLM=1 pytest e2e -q` 全量通过。

## 21. 调试与外部触发（2026-11-20）

这一层解决的是「跑之前怎么确认对」和「跑之外怎么被叫起来」两类问题。两者的共同约束是：
**不能为了调试或便利，绕过引擎已有的幂等、预算、审计与租户边界**。

### 21.1 单节点试跑：同源执行，裁剪语义

画布上的「试跑」不是本地模拟：`engine-api/app/preview.py` 把「目标节点 + 全部上游」裁成一张
小图（保留 `variables` / `defaults`，补一个合成 end 节点收口），连同整份草稿交给
`Dispatcher`，用与正式执行**同一套**执行器、预算账本、错误分类与重试策略跑一遍。

为什么不复用「临时改 DSL 跑全图」：全图会执行目标节点之后的分支，副作用不可控；也不做
「只渲染模板」的假跑，那样模型、检索、连接器都不会真的被调用，用户拿到的还是猜。

裁剪的语义禁区（`UNSUPPORTED_TYPES`）：上游出现 `loop` / `parallel` / `human_gate` 时
直接拒绝（422 `PREVIEW-STRUCTURE-001`）。三者裁剪后会变语义——循环迭代次数、并行分支聚合、
挂起后续跑都不再成立，与其给一个看起来能跑的结果，不如让用户用「运行」跑完整流程。

试跑不落 `workflow_execution`：它是草稿的调试动作，不该出现在执行列表、成本中心与评测里。
可观测性由事件流里的一条 `NODE_PREVIEWED` 审计承载（含 `definitionId` / `nodeKey` /
`status` / `tokenUsed`）。代价是试跑消耗的 token 不进预算账本，这一点写在「已知限制」里。

`Dispatcher.wait()` 用 `asyncio.shield` 等任务收口，超时后置取消位、由调度器在节点边界
取消，避免试跑请求挂着不走。控制面 `ExecutionService.preview` 只做三件事：选 DSL
（入参 > 草稿 > 当前版本）、PII 脱敏、拼请求；权限仍是 `EDITOR`。

### 21.2 运行入参：表单化 + 必填前置

运行弹窗按 `variables` 的 `type` / `required` / `default` 渲染控件；`object` / `array`
退回 JSON 编辑并即时提示解析错误，右上角可切回「JSON 原文」。`missingRequired()` 在触发前
挡住必填缺失——不是为了替代引擎校验，而是避免白造一条注定 `EXEC-INPUT-001` 的执行记录
（执行记录是只增的，脏数据会一直躺在列表里）。引擎侧校验不变，仍是唯一权威。

### 21.3 `defaults.llm`：只影响新建节点

DSL 增加 `defaults.llm`（`connectionId` + `model`），画布新建 llm 节点时继承，弹窗可勾选
批量应用到现有节点。**执行时仍以节点自身 `config` 为准**：如果默认值能覆盖节点配置，
「改了默认模型，线上跑的还是老模型」这类问题会变成「改了默认模型，线上悄悄换了模型」，
两种都不可接受。

### 21.4 外部触发：凭据模型与信任边界

`V010__trigger.sql` 的 `workflow_trigger` 一行对应一个入口，两种类型共用同一套凭据字段：

| 字段 | Webhook | 定时 | 说明 |
|---|---|---|---|
| `token` | 路径里的入口 id | 不使用 | 24 字节 Base64URL，唯一索引；轮换即换 token |
| `secret` | HMAC 密钥 | 不使用 | 32 字节 hex，只在创建/轮换时回显给创建者 |
| `cron_expr` | 不使用 | 5/6 段 cron | 5 段前补 `0` 秒，秒位必须为 `0`，`CronExpression.parse` 校验 |
| `variables_json` | 固定入参 | 固定入参 | 请求体中的同名字段覆盖它 |
| `last_fired_at` | 不使用 | 上次真正跑起来的时刻 | 与 `last_slot_at` 之差就是补偿延迟 |
| `last_slot_at` | 不使用 | 上次执行代表的档位（`V012`） | 判点从它往后推，幂等键也由它生成 |

信任边界的四层：

1. **公开路径白名单**（`PublicPaths.PREFIXES`）只放 `/api/v1/hooks/`，注释明确「只给凭据在
   路径里的入口用，其余不用通配符」；`/api/v1/triggers/**` 仍走 JWT + `TenantFilter`。
2. **签名可选、校验从严**：带 `X-AF-Signature` 就必校验，`MessageDigest.isEqual` 常量时间
   比较；`X-AF-Timestamp` 与服务器偏差超过 300 秒直接拒。不带签名时令牌本身就是 bearer。
3. **限流在服务内**：Redis 固定窗口 `af:hook:rate:{triggerId}:{分钟}`，60 次/分钟。
   `RateLimitFilter` 不覆盖 hooks（它按 JWT 主体计数），所以这一层由 `TriggerService` 自己做。
4. **降权执行**：`triggerFromExternal` 走与用户触发同一个 `dispatch`（幂等、版本解析、政策
   钳制、PII 脱敏），但 `actorId` 是 `trigger:{id}`，审计里能一眼区分是人点的还是外部叫的。

入口探测 `POST /api/v1/hooks/{token}/probe` 只回类型、启用状态与所属工作流，不触发执行、
不回 `secret`——接入方联调时用它确认地址对不对，而不是靠发一次真实请求试。

### 21.5 模型能力标注：数据从哪来

`model_capabilities.describe_model` 把上游 `/models` 的零散字段收敛成四个标签：
`reasoning` / `long-context` / `json` / `vision`，外加 `context_window` 与
`max_output_tokens`。真实来源（以 DeepSeek 为例）：

- `context_window` / `max_output_tokens` / `input_modalities`：上游直接给数字与模态；
- `effort.supported_levels`：只有推理型模型才有的「思考强度」开关，比按名字猜可靠；
- `supported_parameters`（部分网关）：`reasoning` / `response_format` 直接对应能力。

上游只回 id 时退回模型名推断（整词元匹配，`gpt-4o-mini` 不会被 `o1` 误伤），并在
`source` 里标 `inferred`，界面显式写「推断」。**刻意不做家族能力表**：把某家某代的上下文
长度写死在代码里，等厂商换代就成了一句静默的谎话。标签只影响界面提示与 `maxTokens`
建议，不参与任何执行决策。

### 21.6 错误展示：正文之外补一行原因

`describeError` 的正文与 hint 仍是翻译后的固定文案（不暴露内部码），但额外返回 `detail`
——problem+json 里业务代码显式写给人看的那一句（`invalid cron expression: ...`、
`trigger is disabled`）。触发页把它渲染成第二行「后端说明」。普通异常的 `message` 依旧
不透出：只有 `ApiError`（服务端受控文案）才允许走到这一行。

### 21.7 验收

- Python：`test_preview.py`（裁剪、合成 end 的 key 冲突避让、缺节点/不可达/结构拒绝、
  `variables` 与 `defaults` 保留）、`test_model_capabilities.py`（上游字段优先、effort 判推理、
  词元边界、无字段不出标签）；ruff + 324 passed。
- Java：`LlmControllerTest`（`items` 透传）、`WorkflowArchitectureTest`（试跑端点放在执行
  控制器里，守住 `workflow -> execution` 不成环）；`mvn verify` 全模块 BUILD SUCCESS。
- 前端：`triggers-view.spec.ts`（建入口、复制地址、停用、后端原因回显）、
  `model-capabilities.spec.ts` / `model-facts-chips.spec.ts`（标签、推断、`maxTokens` 建议）、
  `canvas-editor.spec.ts`（必填拦截）；typecheck / lint / 249 passed / build 全绿。
- E2E：`e2e/test_node_preview.py`（上游真实执行、不落执行记录、跨租户 404、循环结构 422）、
  `e2e/test_triggers.py`（真实触发、未知 token 404、签名 403/200、幂等、停用 409、probe
  不触发、非法 cron 409、定时 1 分钟内到点）。

## 22. 用量记账与多副本调度（2026-11-20）

这一节记录两个「不显眼但会让数字说谎」的问题，以及为什么修法是现在这样。

### 22.1 试跑为什么必须单独记账

试跑的设计是**不落 `workflow_execution`**（调试动作不该污染执行列表，也不该产生版本）。
代价藏在成本中心里：`BudgetLedgerMapper.accrue` 的 SQL 是

```sql
INSERT INTO budget_ledger (...) SELECT e.tenant_id, ... FROM workflow_execution e
WHERE e.id = #{executionId} ON DUPLICATE KEY UPDATE ...
```

——租户是从 execution 行里查出来的。试跑没有这一行，`INSERT ... SELECT` 匹配不到任何行，
**不报错、不插入、也不记账**。于是「调了十次」在月度账本上是零：额度没动，账单也没有，
只有一条 `NODE_PREVIEWED` 审计躺在事件流里。这不是精度问题，是可见性问题。

修法两条腿：

1. `V011__preview_usage.sql` 建明细表，一次试跑一行（租户 / 工作流 / 节点 / 操作人 / 模型 /
   实际跑过的节点数 / token / 耗时 / 状态）。只写不改——用量是审计数据，写错了追加更正行。
2. `BudgetLedgerMapper.accrueByTenant` 直接按租户累加（`INSERT ... VALUES ... ON DUPLICATE
   KEY UPDATE`），与投影器共用同一个账期口径，所以「本月已用」天然是「执行 + 试跑」。

两个容易做错的地方：

- **调用口径**：`calls_used` 一次试跑加几？加 1 会让「一次试跑跑了两个模型」少记，加 token
  数又没意义。取实际跑过的节点数（`trace` 长度），与投影器按 `NodeCompleted` 累加的口径一致。
- **失败不能连坐**：记账抛异常时试跑结果照常返回（用户要的是节点输出），但必须留 `warn`。
  静默吞掉就等于回到「账面上没有」，只是这次有了日志。

`UsageService.ratio` 现在是唯一口径：平台管理员页（`/api/v1/admin/usage`）与租户自助页
（`/api/v1/usage/summary`）都调它，避免「管理员看到 80%、租户自己看到 92%」这种没法解释的
分歧。按天序列在服务端补 0，前端不负责找洞——空白天在前端补，一旦换渲染方式就会漏。

### 22.2 定时触发：三层防线，各管一件事

原来的实现写着「多实例同时扫描也只会产生一次执行」，靠的是幂等键
`sched-{triggerId}-{LocalDateTime}`。问题在于 `LocalDateTime.toString()` **带秒**：
副本 A 在 07 秒扫到、副本 B 在 41 秒扫到，得到两个不同的键，唯一约束认它们是两次不同的请求，
于是两条执行。文档里的结论是对的，实现是错的。

现在按职责拆成三层，任何一层失效都不会变成「跑两次」：

| 层 | 键 | 作用 | 失效后果 |
|---|---|---|---|
| 扫描租约 | `af:sched:leader`（60s TTL，持有者续期） | 同一时刻只有一个副本扫表 | 多个副本扫表（多几次 SELECT 与日志） |
| 分钟 claim | `af:sched:claim:{id}:{分钟}`（120s TTL） | 同一分钟只有一个副本派发 | 多一次派发尝试 |
| 幂等键 | `sched-{id}-{档位}` + `workflow_execution` 唯一约束 | **正确性**：这一档只有一条执行 | 不可能——冲突走 `DuplicateKeyException` 回放已有执行 |

三条都 fail-open：Redis 抛异常时租约与 claim 返回「可以继续」。方向是刻意选的——漏跑一次
cron（业务方看不见、也不会自动补）比多扫一轮表严重得多；而重复派发已经被幂等键兜住。

租约的续期必须判断「持有者是不是自己」（`GET` 比对 + `EXPIRE`），否则同一个进程在第二轮
就会把自己挡在门外。`GET` 与 `SETNX` 之间的窗口允许两个副本都认为自己拿到租约——这只影响
谁扫表，不影响正确性，因此没有为此引入 Lua 或 Redlock。

触发粒度仍是分钟（秒位不为 0 的 cron 直接拒绝，见 §24），这与上面的 claim / 幂等键口径一致。

## 23. 模型能力标注的第二条来源（2026-11-20）

### 23.1 为什么连接侧也要带

§21.5 的能力标注只覆盖了进程级 `/llm/models`。用户实际更多走「模型连接」（连接器托管端点与
密钥），而那条路径上的探测接口只回 `models: string[]`——画布拿到一串 id，标签只能按名字猜。
「是不是推理型」直接决定 `maxTokens` 该填多少，猜错的代价是节点以 `LLM-RESPONSE-001` 失败
（思维链吃光预算、正文为空）。

于是把 `POST /api/v1/connectors/{id}/models/probe` 的返回扩成
`{ok, models, items, errorCode}`，`items` 里每个模型带 5 个字段：
`contextWindow` / `maxOutputTokens` / `inputModalities` / `supportedParameters` /
`reasoningEffort`。

### 23.2 Java 只搬运，标签只在前端算

三条约束决定了这个切法：

1. **要落库**。探测结果会像 `models` 一样写进 `config_json`，所以只能搬白名单字段。上游
   `/models` 还有 owner、created、权限等与选模型无关的内容，多搬一个就多一份会过期的数据。
2. **别再加第三份规则**。标签规则已经有两处：Python（引擎侧，见 §21.5）与 TS（前端在拿不到
   `items` 时按模型名推断）。再在 Java 抄一份，等于三个地方慢慢长歪。
3. **前端本来就在做这件事**。「引擎没给 → 按名字推断」与「连接给了字段 → 用字段」是同一段
   逻辑的两端，放在一起才不会出现两套口径。

所以 `ConnectorInvoker.modelItems` 只做四件事：按别名取第一个有值的数字（`context_length` /
`context_window` / `max_context_length` / `max_model_len`，输出上限还会看 `top_provider`）、
列表字段小写去重后截断到 16 项、`effort.supported_levels` 非空则 `reasoningEffort=true`、
id 去重排序且条数与长度封顶。**取不到就留 null，不填 0**——界面上「上下文 0」比不显示更糟。

`factsFromProbe` 在前端把这些字段翻成与引擎一致的标签与 `source`：`supported_parameters` 里的
`reasoning` / `response_format`、`effort` 开关、`input_modalities` 含 `image`、
`context_window ≥ 100_000`（阈值与 Python 侧同一个常量），名字推断仍作为兜底。

代价要说清楚：引擎侧与前端各有一份标签规则。这是既有事实（前端本来就要在没有 `items` 时推断），
本次没有把它变成三份。

### 23.3 连接优先于引擎清单

`mergeFacts(连接探测结果, 引擎清单)` 让同名模型取连接的。理由不是「新的比旧的好」，而是两条来源
可能指向完全不同的端点：`deepseek-chat` 在进程级端点是 128k，在另一家网关的转售实例上可能是
32k。谁在服务这次调用，就以谁的字段为准。引擎清单只补连接里没有的模型（比如节点还没配
`connectionId`）。

### 23.4 缓存与失效

`config.modelItems` 是展示用缓存，与 `models` 同生共死：重新探测整份覆盖，探测没回任何能力
字段就整段删掉（`withProbeItems`）。不做「合并保留旧值」——留着上一轮的数字，界面会长期显示
一个早已不成立的上限。连接建得早、库里还没有这份缓存时，界面退回按模型名推断并标「推断」，
点一次「拉取模型」即补齐；这条回退路径保证老数据不会把界面变成空白。

### 23.5 验收

- Java：`ConnectorLlmTest`（带能力字段的上游 → 五项字段齐全、`owner`/`created` 不出现在结果里、
  超长 id 截断、参数小写去重）、`ConnectorServiceTest`（`items` 透传）。
- 前端：`model-capabilities.spec.ts`（字段→标签、`effort` 判推理、0/负数当没给、连接优先于
  引擎、来源为空不报错）、`connectors-view.spec.ts`（刷新覆盖、探测无字段时清缓存）、
  `model-connection.spec.ts`（快速接入把 `modelItems` 一起回写）。

## 24. 定时触发：从「看现在」改成「看档位」（2026-11-20）

§22.2 修掉了多副本的重复执行，但判点逻辑还有一种含糊：`isDue` 只回答「有没有一档到点了」，
真正派发时用的却是**当前分钟**。这两件事在多副本下能凑合，在两个场景里会露馅：

1. **停机恢复**。`last_fired_at` 停在 09:00，服务 09:37 才起来。旧实现里 09:37 这一跑用的是
   `sched-{id}-09:37` ——它不是「09:00 那一档」，只是「09:37 那次扫描」。用户看到的是「日报
   跑在 09:37」，审计里也看不出这一跑迟到了 37 分钟。
2. **派发与记账之间的崩溃**。`triggerFromExternal` 成功、`markFired` 之前进程被杀，重启后
   `last_fired_at` 没变，于是再跑一次——因为键是「新的当前分钟」，唯一约束认它是另一档。

### 24.1 档位（slot）是唯一基准

`TriggerService.dueSlot(row, now)` 从 `last_fired_at` 出发（新触发器取「一分钟前」，所以创建
即触发不会发生），一路向后找**最近一个 ≤ now 的 cron 档位**，返回这个 `Instant`。调度器拿到
档位同时做三件事：claim、派发、记账，幂等键是 `sched-{id}-{档位}`。

于是：

| 场景 | 旧行为 | 现在 |
|---|---|---|
| 正常运行 | 键 = 当前分钟 | 键 = 档位（与当前分钟相同，无变化） |
| 停机 37 分钟后恢复 | 键 = 09:37，看不出迟到 | 键 = 09:00，`lateSeconds=2220`，审计标 `compensated` |
| 派发后崩溃 | 重跑一次 | 重算出同一档 → 幂等回放 |
| 停机 3 小时的每分钟任务 | 跑一次（用当前分钟键） | 跑一次（最近一档），不会补 180 次 |

`workflow_trigger.last_slot_at`（`V012`）就是这次派发代表的档位。它与 `last_fired_at` 分开存，
是因为「跑在什么时候」和「跑的是哪一档」是两个事实：前者的用途是运维排障，后者的用途是
业务对齐（今天这份日报到底算不算今天的）。

### 24.2 补跑是有界的、只补一次

`CATCHUP_WINDOW = 24h`：起点早于窗口就截到窗口边界，之后仍然只取最近一档。这条规则同时是
循环的上界（每分钟一档最多 1440 次 `next()`，另有 1500 的硬上限兜底），所以扫描一轮不会被
一个长期停机的触发器拖死。

为什么不是「把错过的每一档都重放」：那是另一个语义（at-least-once 追赶），需要每档的独立
去重与顺序保证，而业务方真正要的通常是「今天这份还没跑，补上」。补跑一次、并把「迟到多少」
写清楚，比悄悄补 180 条更容易解释。但「只补一次」也不是所有任务想要的——见 §27：这条被改成
按触发器可选的三种策略，默认仍是这里的「只补最近一档」。

### 24.3 秒位必须为 0

`requireCron` 现在拒绝秒位不为 `0` 的 cron（`*/5 * * * * *`）。这不是偷懒：claim 键到分钟、
幂等键到分钟、扫描每 30 秒一轮，秒级 cron 里同一分钟的第二个档位注定被吞掉。与其「配了但只
跑一半」，不如在写入时就报 `WF-STATE-001`，把限制摆在明面上。

### 24.4 可预期性：预览与展示

- `POST /api/v1/workflows/{id}/triggers/cron-preview`：只解析不落库，回 `{cron, timezone, times[]}`。
  5 段写法回填为 6 段规范形式，时间按**触发器时区**格式化（`yyyy-MM-dd HH:mm`）。
- `TriggerView` 多三个字段：`lastSlotAt`（档位）、`nextFireAt` / `nextFireTimes`（接下来三次）。
- 触发页输入 cron 时 350ms 防抖调预览：写错当场看到原因；保存后的触发器显示「上次触发
  <浏览器时区>（09:00 档 · Asia/Shanghai）」与「下次 2026-11-21 09:00（Asia/Shanghai）」。

时间串的两种格式是刻意的：`lastFiredAt` / `createdAt` 是**真实瞬间**，用 ISO（UTC）交给前端
按浏览器时区渲染；`lastSlotAt` / `nextFireAt` / `times` 是**cron 墙钟**，按触发器自己的时区
格式化。混用会出现「每天 09:00 的任务显示成 01:00」这种没法解释的偏差。

### 24.5 验收

- Java：`TriggerServiceTest`（同档同键、档位与墙钟分开记账、未到点返回 null、新触发器只命中
  当前档、停机补最近一档、24 小时窗口、每 15 分钟 cron 的取档、时区渲染、秒级 cron 拒绝、
  预览归一与条数钳制）、`TriggerSchedulerTest`（档位透传、claim 独占、租约与 fail-open）。
- 前端：`triggers-view.spec.ts`（预览展示、预览失败就地报错、档位与下次触发）。
- E2E：`e2e/test_triggers.py`（秒级 cron 409、预览不落触发器、跨租户 404、定时触发后
  `lastSlotAt` / `nextFireAt` 有值；用例结束显式停用，避免「每分钟」的测试触发器一直烧 token）。

## 25. 用量按模型分解与额度条分段（2026-11-20）

§22 解决了「试跑的消耗看不见」，但账面上还是两个笼统的数字：本月用了多少 token、区间里执行
与试跑各占多少。两个问题随之而来：

1. **花在谁身上？** 同一个租户里，`deepseek-chat` 与一个推理模型的单价可以差十几倍，只看总
   token 无法判断「这个月变贵是因为跑得多了，还是因为换模型了」。
2. **额度被谁吃掉了？** 账本是一本账（执行 + 试跑），进度条只有一根。用户看到 82%，却不知道
   其中 30% 是调试——于是要么不敢调，要么调完才发现额度没了。

### 25.1 模型记在节点上，不新开一本账

`V013__node_model.sql` 只给 `node_execution` 加一列 `model`，不新建 ledger：

- 节点行已经是「一次执行里某个节点的一次尝试」的最小粒度，token、延迟、错误码都在上面。
  再建一张 `model_usage` 表就等于把同一批事实写两遍，两份数据一旦漂移就要靠对账解释。
- 按模型聚合是**展示维度**，不是记账维度：`budgets` 仍然按「租户 + 账期」累加一份。
- 查询是 `node_execution JOIN workflow_execution`（租户与时间在执行上，`execution_id` 有索引），
  量级是「区间内的节点行」，与按工作流分解同阶。

记的是 **provider 回落后的真实模型名**（`NodeCompleted.payload.output.model`，LLM 节点在
`nodes/llm.py` 里把 `result.extra["model"] or cfg.model` 写进 output），不是 DSL 里配的那个。
占位名（如 `default`）会被上游替换，记请求名等于把「实际用了什么」记错——而这个字段的唯一
用途就是回答「实际用了什么」。

### 25.2 「不知道」要能表达

三个来源会让模型为空：非 LLM 节点、输出超过 256KB 被溢出成引用（D5，payload 里只剩摘要）、
V013 之前的历史执行。这些行统一进空串桶，前端渲染「未标注 · 非 LLM 或输出未带模型」。

不按「工作流的默认模型」回填：默认模型只是没写 `config.model` 时的兜底，节点完全可以覆盖它，
而且历史数据里到底跑的是哪个模型我们真的不知道。把「不知道」写成「知道」，比留空更危险。

同理，试跑侧的 `preview_usage.model` 也按同样口径合并：同一模型在两个来源里是同一行，因为
用户问的是「这个模型花了多少」，而不是「这个模型在调试时花了多少」——后者用两列分列展示。

### 25.3 额度条的两段：派生，不是第二本账

`budget.previewCalls / previewTokens` 来自 `preview_usage` 的本月合计（`calls` 用
`SUM(node_count)`，与 `accrueByTenant` 的调用口径一致）。它与账本行在**同一个事务**里写，
所以正常情况下 `previewTokens ≤ tokensUsed` 恒成立。

服务端**不做夹取**：账本与明细不一致（记账失败、手工改库）时，宁可让两个数字对不上，也要让
问题露出来。夹取放在前端绘图那一层——`min(previewUtilization, utilization)`，两段宽度之和
永远不超过已用量，墨迹不会画出进度条。

颜色沿用每日柱状图的语义：执行 = accent，试跑 = neutral。同一页面上同一种消耗只有一种颜色，
不需要图例二次解释。

### 25.4 验收

- Java：`UsageServiceTest`（两来源同模型合并、空模型独立成桶、按合计倒序、试跑份额与利用率、
  账本缺行时用配置限额）、`ProjectorWriterTest`（记回落模型、无 output 时留空）。
- 前端：`usage-view.spec.ts`（额度条两段宽度、份额大于已用时封顶、按模型表的合并与「未标注」）。
- E2E：`test_cost_center.py`（`byModel` 里能找到 `DEMO_DSL` 用过的模型且执行 token > 0、
  `previewTokens ≤ tokensUsed`、`previewCalls ≤ callsUsed`）。

## 26. 管理端成本中心：从账本快照到可诊断视图（2026-11-20）

§14 的成本中心接口一直返回 `effectiveLimits`——沿祖先链取最小限额，逻辑是对的，但**前端从来
没渲染它**。结果是页面上的「限额」列显示的是各租户账本行自己写的数字：`Space Alpha` 写着
10,000，而真正生效的是 ORG 的 5,000。管理员据此判断「还有余量」，而新的执行已经被拒了。

这一节修的是「算对了但没人看」和「看了也判断不了」两件事。

### 26.1 有效限额要带来源

`TenantLimitResolver` 沿 `parent_id` 向上走，维护两个维度各自的「当前最小值 + 是谁给的」：

```java
Integer callLimit = ownCallLimit;  String callBy = ownCallLimit == null ? null : tenant.id();
Long    tokenLimit = ownTokenLimit; String tokenBy = ownTokenLimit == null ? null : tenant.id();
while (cursor != null && depth++ < MAX_DEPTH) {
    if (ancestorCalls < callLimit) { callLimit = ancestorCalls; callBy = cursor; }
    ...
}
```

两个维度分开记，因为它们未必由同一个祖先卡住：ORG 可以 `limit_tokens=10,000` 很紧而
`limit_calls=100,000` 很松，TEAM 反过来。只报「限额来自 X」会在这种配置下指错人——而指错人
的代价是管理员跑去改一个改了也没用的账本行。

`limitedByToken == tenantId` 表示这一维由自己决定（前端写「本租户」），否则写「继承自 <名字>」。
「无上限」用 `null` 表达（没有账本行，或限额 ≤ 0），**不用 `Long.MAX_VALUE` 冒充**：那会一路冒到
JSON 与进度条上，前端还得反推这个魔数是什么意思。这与 `UsageService.ratio` 的口径一致——那里
同样把「没有有效限额」折算成 0 利用率。

### 26.2 整页统一账期口径

旧接口混了两种时间口径：`budgetLedger` 是账期，`executions` 是**全部时间**按租户×状态分组。
同一页上「这个月」和「一共」并列，读的人一定会把「失败 8 次」当成这个月的失败数。

现在 `usageByTenantSince(from)` 也按 `created_at` 卡账期，`totals` 是各租户行之和，`byModel` 用
账期起点作为 `from`。三个数字之间可以互相验算，这本身就是一个可用的正确性检查——E2E 里就有一条
断言 `totals.tokens == Σ tenants.tokensUsed`。

### 26.3 跨租户按模型：并集不是和

`byModel` 把执行侧（`node_execution` 按 model 聚合）与试跑侧（`preview_usage`）按模型合并。
「几个租户在用」取两来源的**较大值**而不是相加：同一个租户既可能跑过执行也可能跑过试跑，相加会
把 3 个租户算成 5 个。

### 26.4 状态是结论，利用率是画法

`state` 有四种：`unbounded`（两维都无上限）、`ok`、`near`（任一维 ≥80%）、`over`（任一维已到顶）。
判「超限」用原始数字比较（`used >= limit`），不用夹取后的 `utilization`——`ratio` 把超额压到 1.0，
拿它判断会把「刚好到顶」和「超了三倍」混成一类。展示上仍然只有两种颜色（accent / danger），
与设计令牌「状态色只三种」的约束一致：`near` 用 danger 的文字色，但不改进度条的填充色，避免
「接近」和「已经完了」看起来一样。

### 26.5 验收

- Java：`TenantLimitResolverTest`（自身最紧 / 祖先最紧 / 三级链取祖父 / 两维不同来源 / 无账本行 /
  子无行但继承祖先 / 超额 / 80% 边界 / 限额 0 视为无上限 / parent 成环不死循环）、
  `AdminUsageServiceTest`（账期规范化与拒绝、行字段、汇总、按模型并集、`non_null` 下的缺省字段）。
- 前端：`admin-view.spec.ts`（继承来源、本租户、无上限不画条、两段封顶、超限状态、汇总、按模型、
  账期切换与空账期、错误原因、空态、审计翻页）。
- E2E：`test_cost_center.py`（祖先继承、来源归属、`totals` 与租户行可互相验算、按模型跨租户、
  非法账期 400）。

## 27. 错过档位的三种处置（misfire，2026-11-20）

### 27.1 「只补最近一档」是一条业务判断，不是通用规则

§24.2 把补跑收成「只补最近一档」，理由是「日报迟到 3 天，用户要的是今天的日报」。这个理由
对**日报**成立，对另外两类任务正好相反：

| 任务 | 停机 3 小时后恢复，应该怎么做 |
|---|---|
| 每分钟探活 / 心跳 | 补 180 次毫无意义，最理想是一档都不补 |
| 每小时对账 / 拉单 | 只补最近一次 = 漏掉 2 小时的账，必须补齐 |
| 每日站会提醒 | 下午 3 点补出来一条「9 点站会」的提醒，是纯噪音 |

三种期待互相排斥，用一个「补跑开关」表达不出来。所以在触发器上加了 `misfire_policy`
（`V014`），三档语义：`CATCH_UP_LATEST`（默认）/ `CATCH_UP_ALL` / `SKIP`。

### 27.2 三种策略共用同一段扫描

`TriggerService.dueSlots(row, now)` 返回 `SlotPlan(slots, consumeTo, skipped)`：先把窗口内
**所有**已到点的档位扫出来，再按策略收尾。这样「哪一档算错过」的口径只有一份，三种策略
不可能各自算错；差别只在最后几行：

| 策略 | `slots` | `consumeTo` | `skipped` |
|---|---|---|---|
| `CATCH_UP_LATEST` | 最后一档 | null | 其余档数 |
| `CATCH_UP_ALL` | 最近 20 档（升序） | null | 被丢弃的更老档数 |
| `SKIP` | 空 | 最新一档 | 全部档数 |

`skipped` 不是装饰：它进审计（`TRIGGER_FIRED.skipped` / `TRIGGER_SKIPPED.missed`），
用户问「为什么只跑了一次」时，答案就在这个数字里，不用去猜当时停机了多久。

`CATCH_UP_ALL` 一次派发多档，共用同一分钟的 claim：一个副本拿到 claim 后连续派发整批，
其他副本被挡在外面；每档各自记账（`markFired`），所以中途崩溃时下一轮从断点继续，已经跑过的
档位靠幂等键 `sched-{id}-{档位}` 回放而不是重跑。

### 27.3 SKIP 不能写 last_fired_at

这是实现上最容易踩的一处。`last_fired_at` 被两个地方读：扫描游标，和界面的「上次触发」。
跳过时如果写它，界面就会说「上次触发 15:03」，而实际上一档都没跑——一句假话。

做法是把两件事拆开：

- `SKIP` 只写 `last_slot_at`（+ `last_outcome='SKIP'`），`last_fired_at` 原样保留；
- 扫描游标的基准改成 `max(last_fired_at, last_slot_at)`（`cursorOf`）。档位游标天然就是
  「最后消费掉的档位」，拿它当基准既能让 SKIP 前进，也不会让 FIRE 之后重复算同一档。

`last_outcome`（`FIRE` / `SKIP`）是为了让界面能说清「上次扫描到底跑了没有」：触发页在
`SKIP` 时显示「上次扫描跳过 2026-11-20 09:00 档（未执行）」，而不是把跳过冒充成一次执行。

### 27.4 有界仍然是硬约束

`CATCH_UP_ALL` 的「全部」是「最近 20 档」（`MAX_CATCHUP_SLOTS`）。理由是并发与成本：一次补齐
180 条执行会在同一秒进队列，前 20 条已经把引擎和额度打满，剩下 160 条只是排队等超时。丢弃的
部分记在 `skipped` 里，语义是「有界补偿」而不是「一条不落」——这一点在 README 已知限制里写明。

窗口仍然是 24 小时，三种策略共用；`CATCH_UP_ALL` 在 24 小时内最多也只能补 20 档。

### 27.5 兼容与默认值

- 迁移给 `misfire_policy` 默认 `CATCH_UP_LATEST`，升级不改变既有触发器的行为；
- 读路径用 `parseOrDefault`（脏数据 / 手改过的行退回默认，不打断扫描），写路径用 `require`
  （拼错的值 409，不静默变成别的策略——用户以为「跳过了」而系统在补跑，是最坏的一种沉默）；
- 首次到点（`last_fired_at` 与 `last_slot_at` 都为空）无视策略，一律跑当前这一档：新触发器
  没有「错过」过什么，第一次就跳过会让「每分钟」的任务看起来根本没生效。

### 27.6 验收

- Java：`TriggerServiceTest`（三种策略的 slots / consumeTo / skipped、20 档上界与丢弃最老、
  首次到点无视 SKIP、SKIP 只写游标不写 fired_at、未知值写时拒绝 / 读时回落）、
  `TriggerSchedulerTest`（一批多档全派发、SKIP 只调 `skipMissed`、claim 挡住重复）。
- 前端：`triggers-view.spec.ts`（默认选中、切换后随保存下发、跳过后显示「上次扫描跳过」）。
- E2E：`e2e/test_triggers.py`（策略建 / 改 / 读回一致、不传时为默认值、非法值 409）。

## 28. 执行看护：把「永远运行中」的执行收口（2026-11-20）

### 28.1 投影模式的必然漏洞

§7 定了「事件是唯一真相，MySQL 只是查询索引」。这条原则换来的是可重放与顺序确定，
代价是一个没人兜底的缺口：**终态只可能由事件产生**。于是三种情况下投影会永远停在
`PENDING` / `RUNNING`：

| 断在哪 | 现象 | 原来会发生什么 |
|---|---|---|
| 插入执行行之后、提交引擎之前（进程被杀） | 行是 `PENDING`，引擎从没收到 | 永远 PENDING；取消也走不通（取消是转发给引擎的） |
| 引擎进程崩溃 | 已 `RUNNING` 的执行不再产生事件 | 永远 RUNNING |
| 引擎事件存储被清 / 引擎换了实例 | 事件流里没有这条执行 | 永远 RUNNING，且详情页拉事件也是空 |

引擎侧有自己的 deadline，会发 `ExecutionTimedOut`——但那只在引擎**活着**时成立。
所以缺口不在「超时没实现」，而在「超时的执行者也会死」。

### 28.2 判据只能是引擎状态，不能是「超时了所以它死了」

控制面手里有两个证据：`deadline_at`（承诺）与投影状态（可能是旧的）。
单靠这两个，无法区分三种情形：真的死了、投影器卡住了、引擎还在慢慢收尾。
差别很大——第一种要收口，第二种要**自愈**（把已有的事实抄过来），
第三种什么都不该做。

所以看护先问引擎：`GET /internal/executions/{id}/state`（§28.4 讲为什么要区分 404）。

| 引擎回答 | 含义 | 动作 |
|---|---|---|
| `SUCCEEDED/FAILED/CANCELLED/TIMED_OUT` | 事实已经发生，只是没投递到 | 抄状态 + token/steps（`markTerminal`），审计 `EXECUTION_RECONCILED` |
| `RUNNING` | 投影漏了 `ExecutionStarted` | `markRunning`（`started_at` 用 `IFNULL` 保留首次） |
| `SUSPENDED` | 投影漏了 `GateSuspended` | 先 `markRunning` 再 `markSuspended`（挂起必由运行而来） |
| 与投影一致 | 它真的还在跑 | 什么都不做 |
| 不认识（404）/ 连不上 | 不会再有事件了 | 收口（见 §28.3） |

「什么都不做」这一行是刻意的：**超期本身由引擎负责收口**，控制面只在「无人收口」时兜底。
否则一条被引擎正常超时收口的执行，会被控制面抢先写成别的状态，两边打架。

### 28.3 收口的两种归因

同样是「不会再有事件了」，归因不同，用户要做的事也不同：

| 投影状态 | 落的状态 | 错误码 | 用户该做什么 |
|---|---|---|---|
| `PENDING` | `FAILED` | `EXEC-DISPATCH-LOST` | 重新运行（引擎从没收到过） |
| `RUNNING` | `TIMED_OUT` | `EXEC-DEADLINE_EXCEEDED` | 查为什么没收到终态事件 |

两个码都对前端可见（`errors.ts` 有文案），审计记 `EXECUTION_REAPED`，带
`from/to/errorCode/cause`（`engine-unknown` 或 `engine-unreachable`）——
「为什么这条被判死了」在审计里能直接读出来，不用去猜当时的网络。

### 28.4 为什么要区分「不认识」与「连不上」

`EngineGatewayClient.executionState` 把 404 也归成 `AGT-UPSTREAM-001`（详情页只需要
「拿不到结果」这一种语义）。看护需要更细：404 意味着「这条执行在引擎侧不存在」，
是确定性的事实；网络故障意味着「这次没问出来」，下一次可能就问到了。

于是新增 `findExecutionState` 返回 `Optional`：404 → `empty`，其它故障照旧抛。
两者的动作此刻相同（都收口），但审计的 `cause` 不同，运维据此判断是「引擎清了数据」
还是「引擎抖了一下」。

### 28.5 阈值与「宁可错杀」

- `deadline_at + 120s`：给引擎自己的超时事件留投递余量。窗口内引擎通常已经收口，
  控制面只在它没做到时才补。
- `PENDING` 超过 `180s`：派发是同步调用（`submitExecution` 返回即代表引擎已受理），
  三分钟还停在 PENDING 不可能是「排队中」。
- 候选按 `seq` 升序取，一批最多 50 条（`AF_REAP_BATCH`）：积压时先解决躺得最久的，
  单轮耗时也有上界（每条候选一次引擎调用）。

两个阈值都可配。把 `grace` 调小会让收口更激进，代价是「引擎慢但活着」的执行可能被判死；
调大则相反。默认值的选择是：**宁可晚两分钟收口，也不要错杀一条正在跑的执行**——
但也不能无限等，所以 `grace` 有默认值而不是「等引擎自己好」。

即便如此，仍有错杀的可能：引擎与 MySQL 同时抖动，一条其实还在跑的执行会被写成超时。
这是有意的取舍（README 已知限制 §15 写明）：一条永远挂着的执行比一条被误判的执行
更糟——前者没人知道，后者至少有审计可查，事件仍在引擎侧可人工对账。

### 28.6 多副本与幂等

看护用与定时触发同一个 `RedisLease`（本轮顺手把两处重复的租约代码收成一个类）。
租约只是省重复劳动：真正的安全来自三件事——`markTerminal` 是「写入已知事实」，
重复执行结果相同；候选查询要求「早该结束」，同一行下一轮仍在候选里也不会造成新伤害；
审计重复一条，比漏记一条好。

### 28.7 验收

- Java：`ExecutionReaperTest`（引擎四种回答各自的动作、未知状态不动投影、单条坏行不中断整轮、
  租约被占 / 关闭时不扫表）。
- 前端：`admin-view.spec.ts`（候选表区分两种归因、时长按人读得懂的单位、巡检后重拉候选、空态）。
- E2E：`e2e/test_execution_reaper.py`（非管理员 403、终态执行不进候选、
  `neverStarted` 与状态一致、巡检返回五个计数）。
- 迁移：`V015__execution_reap_index.sql` 补 `(status, deadline_at)` 与 `(status, created_at)`
  两条索引——跨租户扫描用不上 `idx_we_tenant_status`，没有索引时每 60 秒一次全表扫描。
