-- V015__execution_reap_index.sql（P4）--------------------------------------------------
-- 执行看护（ExecutionReaper）每 60 秒跨租户扫一次「还没收口但早该结束」的执行：
--   WHERE status IN ('PENDING','RUNNING') AND (deadline_at < ? OR (status='PENDING' AND created_at < ?))
-- 已有的 idx_we_tenant_status 以 tenant_id 打头，看护不带租户维度，用不上。
-- 没有索引时这一轮就是一次全表扫描，执行表越大越明显（而且每轮都扫）。
--
-- 两条索引各管一个分支：截止时间一条、派发宽限一条。代价是每次写入多维护两个索引，
-- 执行行的写入频率远低于节点行，可以接受。
ALTER TABLE workflow_execution
  ADD KEY idx_we_due (status, deadline_at),
  ADD KEY idx_we_pending (status, created_at);
