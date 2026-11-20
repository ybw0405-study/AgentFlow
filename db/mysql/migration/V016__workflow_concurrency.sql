-- 单个工作流同时进行的执行数上限。
--
-- 0 表示「不限」：升级后既有工作流的行为完全不变，是否限流由使用方显式打开。
-- 限的是「正在占用引擎的执行」（PENDING / RUNNING）；挂起等人工审批的不占，
-- 否则一个等人的审批就能把整条流水线堵死。
ALTER TABLE workflow_definition
  ADD COLUMN max_concurrent INT NOT NULL DEFAULT 0 AFTER status;

-- 并发判定要按 (definition_id, status) 数在跑的执行。
-- 现有 idx_we_def 只有 (definition_id, seq)，数状态还得回表逐行过滤。
CREATE INDEX idx_we_def_status ON workflow_execution (definition_id, status);
