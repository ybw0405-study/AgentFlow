-- 事件表查询索引补齐（architecture.md §3.2）
--
-- 网关审批超时扫描每 15s 跑一次「仍在挂起的 gate」：
--   event_type = 'GateSuspended' AND 其后无同节点 GateResolved
-- 原实现只能全表扫描 execution_event（事件表是全系统增长最快的表），
-- 单执行上千事件后该查询会随总量线性劣化。用部分索引把扫描面收敛到
-- 「确实是挂起/裁决」的少量行，且按 execution_id+node_key 直接命中 NOT EXISTS 条件。

CREATE INDEX idx_ee_gate_suspended
  ON execution_event(execution_id, node_key, aggregate_version)
  WHERE event_type = 'GateSuspended';

CREATE INDEX idx_ee_gate_resolved
  ON execution_event(execution_id, node_key, aggregate_version)
  WHERE event_type = 'GateResolved';
