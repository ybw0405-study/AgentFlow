-- 演示种子（P0 验收）：1 Org + 2 Space + 4 用户（密码统一 agentflow123，仅本地演示）
-- 通过 scripts/start-dev.* 注入，或：docker exec -i agentflow-mysql mysql \
--   --default-character-set=utf8mb4 -u"$AF_MYSQL_ROOT_USER" -p"$AF_MYSQL_ROOT_PASSWORD" agentflow < seed-demo.sql
-- 注意：上例中的 root 账号/密码由环境变量注入，仓库内不含可被直接利用的明文 root 口令。
-- 演示账号 agentflow123 仅用于本地验收，生产环境由 AF_DEMO_ACCOUNTS=false（默认）关闭。
-- 幂等：可重复执行。

INSERT IGNORE INTO tenant (id, name, type, parent_id) VALUES
  ('t-org-demo',   'Demo Org',    'ORG',   NULL),
  ('t-team-core',  'Core Team',   'TEAM',  't-org-demo'),
  ('t-space-a',    'Space Alpha', 'SPACE', 't-team-core'),
  ('t-space-beta', 'Space Beta',  'SPACE', 't-org-demo');

-- 幂等升级：把 Alpha 从 ORG 直挂迁到 Core Team 下（三级：ORG → TEAM → SPACE）
UPDATE tenant SET parent_id = 't-team-core'
  WHERE id = 't-space-a' AND parent_id = 't-org-demo';

-- bcrypt("agentflow123", cost 12)
INSERT IGNORE INTO app_user (id, email, password_hash, display_name, platform_role) VALUES
  ('u-admin',  'admin@agentflow.dev',  '$2b$12$L/O9.uhIGyula1GE4bDm1.d94fbkK6ALxdCVE2VGmTNKSMAZLrFOG', 'Demo Admin',  'ADMIN'),
  ('u-editor', 'editor@agentflow.dev', '$2b$12$L/O9.uhIGyula1GE4bDm1.d94fbkK6ALxdCVE2VGmTNKSMAZLrFOG', 'Demo Editor', 'USER'),
  ('u-viewer', 'viewer@agentflow.dev', '$2b$12$L/O9.uhIGyula1GE4bDm1.d94fbkK6ALxdCVE2VGmTNKSMAZLrFOG', 'Demo Viewer', 'USER'),
  ('u-beta',   'beta@agentflow.dev',   '$2b$12$L/O9.uhIGyula1GE4bDm1.d94fbkK6ALxdCVE2VGmTNKSMAZLrFOG', 'Beta Editor', 'USER'),
  ('u-orgadm', 'orgadmin@agentflow.dev', '$2b$12$L/O9.uhIGyula1GE4bDm1.d94fbkK6ALxdCVE2VGmTNKSMAZLrFOG', 'Org Admin', 'USER');

-- 三级继承验证：u-orgadm 只在 ORG 上是 ADMIN，可继承进入 Core Team 与 Space Alpha/Beta
INSERT IGNORE INTO tenant_member (tenant_id, user_id, role, inherited) VALUES
  ('t-org-demo',   'u-admin',  'OWNER',  0),
  ('t-space-a',    'u-admin',  'OWNER',  0),
  ('t-space-beta', 'u-admin',  'OWNER',  0),
  ('t-space-a',    'u-editor', 'EDITOR', 0),
  ('t-space-a',    'u-viewer', 'VIEWER', 0),
  ('t-space-beta', 'u-beta',   'EDITOR', 0),
  ('t-org-demo',   'u-orgadm', 'ADMIN',  0);

INSERT IGNORE INTO budget_ledger (tenant_id, period, calls_used, tokens_used, limit_calls, limit_tokens)
SELECT id, DATE_FORMAT(NOW(3), '%Y-%m'), 0, 0, 10000, 50000 FROM tenant;

-- 内置知识库：示例里的 retrieval 节点引用 kb-demo，开箱即有真实文档可检索
INSERT IGNORE INTO knowledge_base (id, tenant_id, name, description, created_by)
VALUES ('kb-demo', 't-space-a', '产品与售后知识库',
        '退款、发票与账号安全说明，供示例工作流检索', 'u-admin');

INSERT INTO knowledge_document
  (id, tenant_id, kb_id, title, source, char_count, chunk_count, created_by)
VALUES ('kd-demo-1', 't-space-a', 'kb-demo', '售后与账号常见问题', 'TEXT',
        185, 3, 'u-admin')
ON DUPLICATE KEY UPDATE title = VALUES(title), chunk_count = VALUES(chunk_count);

INSERT INTO knowledge_chunk
  (id, tenant_id, kb_id, document_id, ordinal, document_title, content, token_count)
VALUES
  ('kc-demo-1', 't-space-a', 'kb-demo', 'kd-demo-1', 0, '售后与账号常见问题', '退款政策：购买后七天内可申请全额退款，在订单页提交申请并填写订单编号，审核通过后原路退回。超过七天、三十天内可申请换货或维修。', 21),
  ('kc-demo-2', 't-space-a', 'kb-demo', 'kd-demo-1', 1, '售后与账号常见问题', '发票开具：增值税普通发票在订单完成后三个工作日内开出，专票需补充开票资料。逾期未收到可联系财务组补开，电子发票发送至注册邮箱。', 21),
  ('kc-demo-3', 't-space-a', 'kb-demo', 'kd-demo-1', 2, '售后与账号常见问题', '账号安全：登录验证码收不到时，先检查短信拦截与手机号是否绑定；连续五次输错密码会锁定三十分钟，可通过绑定邮箱自助解锁。', 19)
ON DUPLICATE KEY UPDATE content = VALUES(content), document_title = VALUES(document_title);

-- 内置示例工作流「LLM → 文档检索 → LLM」（已发布 v4）
INSERT INTO workflow_definition
  (id, tenant_id, name, description, status, current_version_id, created_by)
VALUES
  ('wf-demo', 't-space-a', '示例：LLM → 文档检索 → LLM',
   '起草、检索、综合回答三段串联；模型走 OpenAI 兼容端点，检索命中内置知识库里的真实文档。',
   'PUBLISHED', 'wfv-demo-4', 'u-admin')
ON DUPLICATE KEY UPDATE current_version_id = VALUES(current_version_id),
                         name = VALUES(name), description = VALUES(description);

INSERT INTO workflow_version
  (id, definition_id, version_no, dsl_json, dsl_checksum, immutable, published_at, published_by, created_by)
VALUES
  ('wfv-demo-4', 'wf-demo', 4, '{"defaults":{"maxParallelism":4,"retry":{"backoffMs":1000,"backoffMultiplier":2.0,"maxAttempts":3},"timeoutMs":30000},"dslVersion":"1.0","edges":[{"from":"start","to":"draft"},{"from":"start","to":"enrich"},{"from":"draft","to":"final"},{"from":"enrich","to":"final"},{"from":"final","to":"end"}],"nodes":[{"config":{},"key":"start","position":{"x":60.0,"y":180.0},"type":"start"},{"config":{"maxTokens":1024,"model":"deepseek-chat","outputMode":"text","promptTemplate":"请根据问题起草答案要点：{{ vars.question }}","provider":"openai-compatible","temperature":0.7},"key":"draft","name":"起草要点","position":{"x":300.0,"y":60.0},"type":"llm"},{"config":{"knowledgeBaseIds":["kb-demo"],"minScore":0.0,"queryTemplate":"{{ vars.question }}","rerank":false,"topK":5},"key":"enrich","name":"检索增强","position":{"x":300.0,"y":300.0},"type":"retrieval"},{"config":{"maxTokens":1024,"model":"deepseek-chat","outputMode":"text","promptTemplate":"结合要点与检索给出最终回答。\\n要点：{{ nodes.draft.output.text }}\\n检索：{{ nodes.enrich.output.result }}\\n问题：{{ vars.question }}","provider":"openai-compatible","temperature":0.7},"key":"final","name":"综合回答","position":{"x":560.0,"y":180.0},"type":"llm"},{"config":{"resultMapping":{"answer":"{{ nodes.final.output.text }}"}},"key":"end","position":{"x":800.0,"y":180.0},"type":"end"}],"variables":{"question":{"pii":true,"required":true,"type":"string"}}}', 'ac067a62a6d9caa42ebf238bac9f1f2594303a6068848acda379469dcbef65eb', 1, NOW(3), 'u-admin', 'u-admin')
ON DUPLICATE KEY UPDATE id = id;

-- 内置示例工作流「客户工单分诊」（已发布 v4）
INSERT INTO workflow_definition
  (id, tenant_id, name, description, status, current_version_id, created_by)
VALUES
  ('wf-demo-triage', 't-space-a', '示例：客户工单分诊',
   '分诊、加急判定、并行取证、人工审批、逐条润色、回复；取证改为检索知识库原文。',
   'PUBLISHED', 'wfv-demo-triage-4', 'u-admin')
ON DUPLICATE KEY UPDATE current_version_id = VALUES(current_version_id),
                         name = VALUES(name), description = VALUES(description);

INSERT INTO workflow_version
  (id, definition_id, version_no, dsl_json, dsl_checksum, immutable, published_at, published_by, created_by)
VALUES
  ('wfv-demo-triage-4', 'wf-demo-triage', 4, '{"defaults":{"maxParallelism":4,"retry":{"backoffMs":1000,"backoffMultiplier":2.0,"maxAttempts":3},"timeoutMs":30000},"dslVersion":"1.0","edges":[{"from":"start","to":"triage"},{"from":"triage","to":"route"},{"branch":"urgent","from":"route","to":"gather"},{"branch":"normal","from":"route","to":"gather"},{"from":"gather","to":"review"},{"branch":"approved","from":"review","to":"polish"},{"branch":"rejected","from":"review","to":"notify"},{"from":"polish","to":"reply"},{"from":"reply","to":"set_reply"},{"from":"set_reply","to":"close"},{"from":"notify","to":"close"}],"nodes":[{"config":{},"key":"start","position":{"x":60.0,"y":320.0},"type":"start"},{"config":{"maxTokens":1024,"model":"deepseek-chat","outputMode":"text","promptTemplate":"对下面这条工单做初步分诊：给出问题类别、影响面与建议处理路径。\\n{{ vars.ticket }}","provider":"openai-compatible","temperature":0.7},"key":"triage","name":"初步分诊","position":{"x":240.0,"y":320.0},"type":"llm"},{"config":{"cases":[{"name":"urgent","when":"vars.urgent == true"},{"name":"normal","when":"vars.urgent == false"}],"elseBranch":"normal"},"key":"route","name":"加急判定","position":{"x":420.0,"y":320.0},"type":"condition"},{"config":{"branches":{"crm":{"edges":[{"from":"crm_hit","to":"crm_note"}],"nodes":[{"config":{"knowledgeBaseIds":["kb-demo"],"minScore":0.0,"queryTemplate":"{{ vars.ticket }}","rerank":false,"topK":5},"key":"crm_hit","name":"客户档案检索","position":{"x":0.0,"y":0.0},"type":"retrieval"},{"config":{"mappings":[{"target":"vars.crmNote","template":"{{ nodes.crm_hit.output.result }}"}]},"key":"crm_note","name":"归档结论","position":{"x":0.0,"y":0.0},"type":"transform"}]},"kb":{"edges":[{"from":"kb_hit","to":"kb_note"}],"nodes":[{"config":{"knowledgeBaseIds":["kb-demo"],"minScore":0.0,"queryTemplate":"{{ vars.ticket }}","rerank":false,"topK":5},"key":"kb_hit","name":"知识库检索","position":{"x":0.0,"y":0.0},"type":"retrieval"},{"config":{"mappings":[{"target":"vars.kbNote","template":"{{ nodes.kb_hit.output.result }}"}]},"key":"kb_note","name":"归档结论","position":{"x":0.0,"y":0.0},"type":"transform"}]}},"join":"all","maxConcurrency":2},"key":"gather","name":"并行取证","position":{"x":600.0,"y":320.0},"type":"parallel"},{"config":{"approvers":{"expression":"user.platformAdmin == true","type":"EXPRESSION"},"onTimeout":{"action":"REJECT"},"promptTemplate":"加急工单请确认回复口径：\\n{{ vars.ticket }}","timeoutMs":86400000},"key":"review","name":"加急审批","position":{"x":780.0,"y":320.0},"type":"human_gate"},{"config":{"body":{"edges":[{"from":"polish_one","to":"polish_sign"}],"nodes":[{"config":{"mappings":[{"target":"vars.polishLine","template":"{{ vars.item }}：已按品牌口径润色"}]},"key":"polish_one","name":"润色","position":{"x":0.0,"y":0.0},"type":"transform"},{"config":{"mappings":[{"target":"vars.draftNote","template":"{{ vars.polishLine }}（第 {{ vars.index }} 条）"}]},"key":"polish_sign","name":"留痕","position":{"x":0.0,"y":0.0},"type":"transform"}]},"concurrency":1,"indexVar":"index","itemVar":"item","itemsExpression":"[\\"知识库\\", \\"话术\\"]","maxIterations":10},"key":"polish","name":"逐条润色","position":{"x":960.0,"y":320.0},"type":"loop"},{"config":{"maxTokens":1024,"model":"deepseek-chat","outputMode":"text","promptTemplate":"工单：{{ vars.ticket }}\\n并行取证结论已归档，{{ nodes.polish.output.count }} 条要点逐条润色完成。\\n请写出给客户的最终回复。","provider":"openai-compatible","temperature":0.7},"key":"reply","name":"撰写回复","position":{"x":1140.0,"y":320.0},"type":"llm"},{"config":{"mappings":[{"target":"vars.outcome","template":"{{ nodes.reply.output.text }}"}]},"key":"set_reply","name":"归档回复","position":{"x":1320.0,"y":320.0},"type":"transform"},{"config":{"mappings":[{"target":"vars.outcome","template":"审批未通过，转人工复核"}]},"key":"notify","name":"转人工复核","position":{"x":1140.0,"y":520.0},"type":"transform"},{"config":{"resultMapping":{"outcome":"{{ vars.outcome }}"}},"key":"close","name":"收口","position":{"x":1500.0,"y":320.0},"type":"end"}],"variables":{"crmNote":{"pii":false,"required":false,"type":"string"},"draftNote":{"pii":false,"required":false,"type":"string"},"kbNote":{"pii":false,"required":false,"type":"string"},"outcome":{"pii":false,"required":false,"type":"string"},"polishLine":{"pii":false,"required":false,"type":"string"},"ticket":{"pii":true,"required":true,"type":"string"},"urgent":{"pii":false,"required":true,"type":"boolean"}}}', 'b0c7a47ed205f654102db9851751a2305ab80ffb0d91bd260854ba2132384bd5', 1, NOW(3), 'u-admin', 'u-admin')
ON DUPLICATE KEY UPDATE id = id;
