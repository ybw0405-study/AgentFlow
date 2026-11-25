-- V004 治理：幂等键、审计（只追加）、预算账本、事件投影游标
CREATE TABLE idempotency_key (
  tenant_id CHAR(36) NOT NULL,
  scope VARCHAR(64) NOT NULL,
  ikey VARCHAR(128) NOT NULL,
  result_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (tenant_id, scope, ikey),
  KEY idx_ik_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE audit_event (
  seq BIGINT AUTO_INCREMENT UNIQUE,
  id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  execution_id CHAR(36) NULL,
  actor_id CHAR(36) NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_redacted JSON NULL,
  token_used INT NULL,
  latency_ms INT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ae_tenant (tenant_id, seq),
  KEY idx_ae_trace (trace_id),
  KEY idx_ae_exec (execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$
CREATE TRIGGER trg_ae_no_update BEFORE UPDATE ON audit_event FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUD-APPENDONLY-001';
END$$
CREATE TRIGGER trg_ae_no_delete BEFORE DELETE ON audit_event FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUD-APPENDONLY-001';
END$$
DELIMITER ;

CREATE TABLE budget_ledger (
  tenant_id CHAR(36) NOT NULL,
  period CHAR(7) NOT NULL,
  calls_used INT NOT NULL DEFAULT 0,
  tokens_used BIGINT NOT NULL DEFAULT 0,
  limit_calls INT NOT NULL,
  limit_tokens BIGINT NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE projector_cursor (
  projector VARCHAR(64) NOT NULL,
  last_seq BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (projector)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
