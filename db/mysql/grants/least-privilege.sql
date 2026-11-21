-- 最小授权（迁移完成后执行）：
-- af_app：业务表全 DML；audit_event 仅 SELECT/INSERT（审计只追加的 DB 层保证）
-- af_audit：audit_event SELECT/INSERT（audit-service 专用账号）
-- 生产环境密码必须经密钥管理注入，禁止沿用 dev 口令。
--
-- 覆盖 db/mysql/migration/V001~V017 的全部 CREATE TABLE；由
-- scripts/check-grants.py 守卫「迁移表集合 == 授权表集合」，新增迁移时务必同步本文件。
-- 授权策略：
--   * audit_event 只追加：任何账号都只给 SELECT/INSERT（配合 trg_ae_no_update/no_delete）
--   * outbox 表（gate_resolve_outbox / audit_outbox）需 SELECT/INSERT/UPDATE/DELETE：
--     relay 要改状态、清已投递行，失败降级直写也要能 INSERT
--   * 其余业务表：af_app 全 DML

-- V001 租户与用户
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.tenant              TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.tenant_member       TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.app_user            TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.refresh_token       TO 'af_app'@'%';

-- V002 工作流定义与不可变版本
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.workflow_definition TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.workflow_version    TO 'af_app'@'%';

-- V003 执行索引
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.workflow_execution  TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.node_execution      TO 'af_app'@'%';

-- V004 治理：幂等键 / 审计（只追加）/ 预算账本 / 投影游标
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.idempotency_key     TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.budget_ledger       TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.projector_cursor    TO 'af_app'@'%';
GRANT SELECT, INSERT               ON agentflow.audit_event           TO 'af_app'@'%';
GRANT SELECT, INSERT               ON agentflow.audit_event           TO 'af_audit'@'%';

-- V005 连接器与凭据
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.tool_connector      TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.tool_credential     TO 'af_app'@'%';

-- V006 审批 / 通知 / 裁决 outbox / 评测 / A-B 实验
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.gate_task           TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.notification        TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.gate_resolve_outbox TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.eval_dataset        TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.eval_case           TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.eval_run            TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.eval_case_result    TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.ab_experiment       TO 'af_app'@'%';

-- V007 审计 outbox（relay 需改状态 / 清理，故全 DML）
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.audit_outbox        TO 'af_app'@'%';

-- V009 知识库
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.knowledge_base      TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.knowledge_document  TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.knowledge_chunk     TO 'af_app'@'%';

-- V010 外部触发
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.workflow_trigger    TO 'af_app'@'%';

-- V011 试跑用量
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.preview_usage       TO 'af_app'@'%';

FLUSH PRIVILEGES;
