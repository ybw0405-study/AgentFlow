-- 最小授权（迁移完成后执行）：
-- af_app：业务表全 DML；audit_event 仅 SELECT/INSERT（审计只追加的 DB 层保证）
-- af_audit：audit_event SELECT/INSERT（audit-service 专用账号）
-- 生产环境密码必须经密钥管理注入，禁止沿用 dev 口令。

GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.tenant              TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.tenant_member       TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.app_user            TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.refresh_token       TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.workflow_definition TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.workflow_version    TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.workflow_execution  TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.node_execution      TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.idempotency_key     TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.budget_ledger       TO 'af_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON agentflow.projector_cursor    TO 'af_app'@'%';
GRANT SELECT, INSERT               ON agentflow.audit_event           TO 'af_app'@'%';

GRANT SELECT, INSERT               ON agentflow.audit_event           TO 'af_audit'@'%';

FLUSH PRIVILEGES;
