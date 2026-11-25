# 数据模型

> 本文件由 `docs/architecture.md` 按主题拆分归档，正文保持原文（含原章节编号）。
> 章节索引与 `§` 交叉引用对照见 [docs/README.md](../README.md)。

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

CREATE TABLE app_user (                                 -- 复用 DocQA 结构
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

CREATE TABLE refresh_token (                            -- 复用 DocQA 轮换模型
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

> 上面的 `trg_wv_no_update` 是 **V002 的初始定义**。`V019__immutable_version_columns.sql`
> 会把不可变保护扩展到 `published_at` / `published_by` / `immutable` 本身； 后该迁移
> 改为「先建后删」：先 `CREATE TRIGGER IF NOT EXISTS trg_wv_no_update_af10`（新定义），
> 再 `DROP TRIGGER trg_wv_no_update`（V002 旧定义），避免 DDL 隐式提交造成保护空窗。
> 因此**升级后库里的 BEFORE UPDATE 触发器名是 `trg_wv_no_update_af10`**，
> `trg_wv_no_delete` 不变。回滚脚本见 `scripts/rollback/`，顺序见 `scripts/ROLLBACK.md`。

### 3.2 PostgreSQL（库 `agentflow_events`，独立迁移，Python 独占写入）

```sql
-- V001__event_log.sql -----------------------------------------------------------
CREATE TABLE execution_event (
  seq               BIGSERIAL PRIMARY KEY,               -- 全局序（projector 游标）
  execution_id      UUID        NOT NULL,
  event_type        TEXT        NOT NULL,                -- 见 §5.2 事件目录
  node_key          TEXT        NULL,
  payload           JSONB       NOT NULL,                -- 口径见决策表 D5（溢出落 node_io）
  aggregate_version INT         NOT NULL,                -- 每 execution 严格递增
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_ee_version ON execution_event(execution_id, aggregate_version);
CREATE INDEX        idx_ee_exec    ON execution_event(execution_id, seq);

CREATE TABLE execution_tracker (                         -- 恢复扫描用，与事件同 TX 更新
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

