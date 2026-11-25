-- 预算账本累加的事件级幂等标记。
-- 投影器的 Redis 租约是优化、Redis 故障 fail-open，多副本可能同时投影同一批 NodeCompleted，
-- 导致 budget_ledger 重复累加、用户月度配额被错误扣减。用事件 seq 作唯一键做代码层兜底。
-- 注意：已应用的迁移文件不可修改（Flyway 不可变纪律）；排序规则调整见 V022。
CREATE TABLE IF NOT EXISTS budget_ledger_seen (
  event_seq BIGINT NOT NULL,
  execution_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (event_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
