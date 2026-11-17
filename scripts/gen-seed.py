"""从 scripts/_demo-workflow.json 生成幂等 seed-demo.sql（正确处理 JSON 转义）。"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BS = chr(92)  # 反斜杠
SQ = chr(39)  # 单引号

meta = json.loads((ROOT / "scripts" / "_demo-workflow.json").read_text(encoding="utf-8"))
sql_json = meta["canonical"].replace(BS, BS + BS).replace(SQ, SQ + SQ)
checksum = meta["checksum"]

header = """-- 演示种子（P0 验收）：1 Org + 2 Space + 4 用户（密码统一 agentflow123，仅本地演示）
-- 通过 scripts/start-dev.* 或 docker exec -i agentflow-mysql mysql -uroot -proot agentflow < seed-demo.sql 注入
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
"""

demo = f"""
-- 内置示例工作流「LLM → 工具 → LLM」（已发布 v1）
INSERT INTO workflow_definition
  (id, tenant_id, name, description, status, current_version_id, created_by)
VALUES
  ('wf-demo', 't-space-a', '示例：LLM → 工具 → LLM',
   '内置演示：mock LLM 起草 → mock.search 检索 → mock LLM 综合。离线可跑。',
   'PUBLISHED', 'wfv-demo-1', 'u-admin')
ON DUPLICATE KEY UPDATE current_version_id = VALUES(current_version_id);

INSERT INTO workflow_version
  (id, definition_id, version_no, dsl_json, dsl_checksum, immutable, published_at, published_by, created_by)
VALUES
  ('wfv-demo-1', 'wf-demo', 1, '{sql_json}', '{checksum}', 1, NOW(3), 'u-admin', 'u-admin')
ON DUPLICATE KEY UPDATE id = id;
"""

out = header + demo
(ROOT / "scripts" / "seed-demo.sql").write_text(out, encoding="utf-8", newline="\n")
print("seed-demo.sql regenerated,", len(out), "bytes")
