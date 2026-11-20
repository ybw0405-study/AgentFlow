-- 外部触发：Webhook（带令牌的公开入口）与定时（cron）。
-- 令牌即凭据：URL 里的 token 足够随机，泄露即可触发，因此支持轮换；
-- secret 用于校验 X-AF-Signature（HMAC-SHA256），签名可选但一旦带上就必须正确。
CREATE TABLE workflow_trigger (
  seq           BIGINT AUTO_INCREMENT UNIQUE,
  id            CHAR(36)     NOT NULL,
  definition_id CHAR(36)     NOT NULL,
  tenant_id     CHAR(36)     NOT NULL,
  trigger_type  ENUM('WEBHOOK','SCHEDULE') NOT NULL,
  token         VARCHAR(64)  NOT NULL,
  secret        VARCHAR(128) NOT NULL,
  cron_expr     VARCHAR(64)  NULL,
  timezone      VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
  variables_json JSON        NULL,
  version_id    CHAR(36)     NULL,
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  last_fired_at DATETIME(3)  NULL,
  created_by    VARCHAR(64)  NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_wt_token (token),
  KEY idx_wt_definition (definition_id, tenant_id),
  KEY idx_wt_schedule (trigger_type, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
