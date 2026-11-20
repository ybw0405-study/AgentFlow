-- 节点失败原因落行。
--
-- 引擎的 NodeFailed 事件本来就有 message（截断 500 字，例如
-- "branch 'kb' failed: DSL-REF-001 ..."），但早前只投影 errorCode，
-- 界面上只能看到 PARALLEL-BRANCH-FAILED 这类代码，排查必须回事件流翻。
-- 这里把原因存到行上，执行详情与节点树直接可见。
ALTER TABLE node_execution
  ADD COLUMN error_message VARCHAR(500) NULL AFTER error_code;
