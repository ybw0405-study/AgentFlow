-- V005__connector.sql（P2）-------------------------------------------------------
-- 连接器注册表与凭据。凭据只存密文（AES-256-GCM，AAD 绑定 connector_id+tenant_id），
-- 明文只在 connector-service 解密后注入出网请求头，Python 侧永不可见。
CREATE TABLE tool_connector (
  id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(64) NOT NULL,
  auth_type ENUM('NONE','API_KEY','BASIC','OAUTH2') NOT NULL DEFAULT 'NONE',
  config_json JSON NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  uq_name VARCHAR(128) GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_tc_tenant_name (tenant_id, uq_name),
  KEY idx_tc_tenant (tenant_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tool_credential (
  id CHAR(36) NOT NULL,
  connector_id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  encrypted_secret VARBINARY(2048) NULL,
  oauth_token_enc VARBINARY(4096) NULL,
  key_id VARCHAR(64) NOT NULL,
  expires_at DATETIME(3) NULL,
  scope VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_tcr_conn (connector_id),
  KEY idx_tcr_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
