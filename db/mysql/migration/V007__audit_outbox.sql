-- 审计 outbox：先落库、再投递。
--
-- 为什么需要它：审计是治理要求（谁在什么时候改了/跑了什么），MQ 抖动、broker 重启、
-- producer 连接重建这些事一定会发生。旧实现直接 send，失败只打一行 ERROR 就丢事件，
-- 而「丢了哪些」无从知晓。现在写业务与写 outbox 同库，事件先落地，再由 relay 按退避
-- 重投；只要库还在，事件就不会消失。
--
-- status 只有 PENDING / SENT：审计不允许「放弃投递」，重试次数只用于退避与告警，
-- 不做终态收敛（与 gate_resolve_outbox 不同——那边超时后审批本身已失效，重投无意义）。
CREATE TABLE audit_outbox (
  seq             BIGINT AUTO_INCREMENT UNIQUE,
  id              CHAR(36)     NOT NULL,
  tenant_id       CHAR(36)     NOT NULL,
  event_type      VARCHAR(64)  NOT NULL,
  -- 完整 AuditMessage JSON：relay 不重新组装，避免字段漂移
  message_json    JSON         NOT NULL,
  status          ENUM('PENDING','SENT') NOT NULL DEFAULT 'PENDING',
  attempts        INT          NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_error      VARCHAR(500) NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at         DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_ao_due (status, next_attempt_at),
  KEY idx_ao_sent (status, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
