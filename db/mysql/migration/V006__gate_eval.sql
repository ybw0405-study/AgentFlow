-- V006__gate_eval.sql（P3）-----------------------------------------------------
-- 1) 执行状态增加 SUSPENDED：human_gate 挂起期间不占用 worker，也不落终态。
-- 状态仍是「事件折叠的结果」，GateSuspended/GateResolved 是权威来源。
ALTER TABLE workflow_execution
  MODIFY status ENUM('PENDING','RUNNING','SUSPENDED','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')
  NOT NULL DEFAULT 'PENDING';

-- 2) 审批待办：GateSuspended 投影而来，approvers_json 是「冻结的审批人快照」。
-- 冻结而非实时解析，是为了保证「当时有资格的人」在成员变动后仍能处理，
-- 且待办列表可纯索引查询（JSON_CONTAINS）。
CREATE TABLE gate_task (
  seq BIGINT AUTO_INCREMENT UNIQUE,
  id CHAR(36) NOT NULL,
  execution_id CHAR(36) NOT NULL,
  node_key VARCHAR(160) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  definition_id CHAR(36) NULL,
  prompt TEXT NOT NULL,
  approvers_json JSON NOT NULL,
  approver_rule_json JSON NULL,
  status ENUM('PENDING','APPROVED','REJECTED','TIMEOUT','CANCELLED')
                    NOT NULL DEFAULT 'PENDING',
  decision VARCHAR(32) NULL,
  resolved_by CHAR(36) NULL,
  comment VARCHAR(1024) NULL,
  timeout_at DATETIME(3) NULL,
  default_branch VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_gate_exec_node (execution_id, node_key),
  KEY idx_gate_inbox (tenant_id, status, seq),
  KEY idx_gate_due (status, timeout_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3) 站内通知（architecture.md §3.1）
CREATE TABLE notification (
  seq BIGINT AUTO_INCREMENT UNIQUE,
  id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  type VARCHAR(64) NOT NULL,
  payload_json JSON NULL,
  read_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ntf_user (user_id, read_at, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4) 裁决 outbox：引擎不可达时裁决必须先落地（用户已经点了按钮），再由 relay 重投。
-- 比「直接 500 让用户重试」更可靠：审批动作不可丢，且重复投递在引擎侧幂等
-- （节点已终态 → ALREADY_RESOLVED）。
CREATE TABLE gate_resolve_outbox (
  id CHAR(36) NOT NULL,
  execution_id CHAR(36) NOT NULL,
  node_key VARCHAR(160) NOT NULL,
  decision VARCHAR(32) NOT NULL,
  resolved_by CHAR(36) NOT NULL,
  comment VARCHAR(1024) NULL,
  status ENUM('PENDING','DONE','FAILED') NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_error VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_outbox_exec_node (execution_id, node_key),
  KEY idx_outbox_due (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5) 评测集：数据集 → 用例（输入/期望/断言）→ 运行 → 逐用例结果。
CREATE TABLE eval_dataset (
  seq BIGINT AUTO_INCREMENT UNIQUE,
  id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  uq_name VARCHAR(128) GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_ed_tenant_name (tenant_id, uq_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE eval_case (
  id CHAR(36) NOT NULL,
  dataset_id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  name VARCHAR(160) NOT NULL,
  input_json JSON NOT NULL,
  expected_json JSON NULL,
  assertion_json JSON NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ec_dataset (dataset_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE eval_run (
  id CHAR(36) NOT NULL,
  dataset_id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  definition_id CHAR(36) NOT NULL,
  version_id CHAR(36) NOT NULL,
  status ENUM('RUNNING','SUCCEEDED','FAILED','CANCELLED') NOT NULL DEFAULT 'RUNNING',
  total INT NOT NULL DEFAULT 0,
  passed INT NOT NULL DEFAULT 0,
  failed INT NOT NULL DEFAULT 0,
  experiment_id CHAR(36) NULL,
  variant VARCHAR(8) NULL,
  error_message VARCHAR(500) NULL,
  created_by CHAR(36) NOT NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_er_dataset (dataset_id, created_at),
  KEY idx_er_status (status, created_at),
  KEY idx_er_experiment (experiment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE eval_case_result (
  id CHAR(36) NOT NULL,
  run_id CHAR(36) NOT NULL,
  case_id CHAR(36) NOT NULL,
  case_name VARCHAR(160) NOT NULL,
  execution_id CHAR(36) NULL,
  status ENUM('PENDING','RUNNING','PASSED','FAILED','ERROR') NOT NULL DEFAULT 'PENDING',
  passed TINYINT(1) NULL,
  actual_json JSON NULL,
  latency_ms INT NULL,
  token_used INT NULL,
  error_message VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ecr_run_case (run_id, case_id),
  KEY idx_ecr_run (run_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6) A/B 实验：同一数据集上跑两个版本，按通过率/耗时/token 出对比结论。
-- 在线流量切分由 P1 的 canary 负责；这里是「离线成对评估」，不混用。
CREATE TABLE ab_experiment (
  id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  definition_id CHAR(36) NOT NULL,
  dataset_id CHAR(36) NOT NULL,
  name VARCHAR(160) NOT NULL,
  version_a CHAR(36) NOT NULL,
  version_b CHAR(36) NOT NULL,
  run_a CHAR(36) NULL,
  run_b CHAR(36) NULL,
  status ENUM('RUNNING','DONE','FAILED') NOT NULL DEFAULT 'RUNNING',
  summary_json JSON NULL,
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_ab_tenant (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
