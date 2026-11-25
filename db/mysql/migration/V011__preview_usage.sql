-- V011 试跑用量：试跑不落 workflow_execution，但真实模型产生的 token 必须可追溯。
-- 只记调试用量，正式执行的用量仍在 workflow_execution（不重复落一份）。
CREATE TABLE preview_usage (
  seq BIGINT AUTO_INCREMENT UNIQUE,
  id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  definition_id CHAR(36) NOT NULL,
  node_key VARCHAR(64) NOT NULL,
  actor_id VARCHAR(64) NULL,
  status VARCHAR(24) NOT NULL,
  model VARCHAR(128) NULL,
  node_count INT NOT NULL DEFAULT 0,
  token_used INT NOT NULL DEFAULT 0,
  latency_ms INT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_pu_tenant_time (tenant_id, created_at),
  KEY idx_pu_definition (definition_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
