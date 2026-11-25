"""从 scripts/_demo-workflow.json、_demo-triage-workflow.json 生成幂等 seed-demo.sql。

演示 DSL 的 canonical 串与 checksum 必须与服务端落库时一致（sha256 of 排序紧凑 JSON），
否则发布校验与重放会判“版本被篡改”。JSON 里的反斜杠、单引号在这里统一转义，避免手写 SQL 出错。
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BS = chr(92) # 反斜杠
SQ = chr(39) # 单引号


def sql_literal(meta: dict) -> tuple[str, str]:
    return meta["canonical"].replace(BS, BS + BS).replace(SQ, SQ + SQ), meta["checksum"]


# 内置示例：id / version id / 名称 / 说明 / DSL 文件
DEMOS = [
    (
        "wf-demo",
        "wfv-demo-4",
        "示例：LLM → 文档检索 → LLM",
        "起草、检索、综合回答三段串联；模型走 OpenAI 兼容端点，检索命中内置知识库里的真实文档。",
        "_demo-workflow.json",
    ),
    (
        "wf-demo-triage",
        "wfv-demo-triage-4",
        "示例：客户工单分诊",
        "分诊、加急判定、并行取证、人工审批、逐条润色、回复；取证改为检索知识库原文。",
        "_demo-triage-workflow.json",
    ),
]

header = """-- 演示种子（P0 验收）：1 Org + 2 Space + 4 用户（密码统一 agentflow123，仅本地演示）
-- 通过 scripts/start-dev.* 或 docker exec -i agentflow-mysql mysql --default-character-set=utf8mb4 -uroot -proot agentflow < seed-demo.sql 注入
-- 幂等：可重复执行。

INSERT IGNORE INTO tenant (id, name, type, parent_id) VALUES
  ('t-org-demo', 'Demo Org', 'ORG', NULL),
  ('t-team-core', 'Core Team', 'TEAM', 't-org-demo'),
  ('t-space-a', 'Space Alpha', 'SPACE', 't-team-core'),
  ('t-space-beta', 'Space Beta', 'SPACE', 't-org-demo');

-- 幂等升级：把 Alpha 从 ORG 直挂迁到 Core Team 下（三级：ORG → TEAM → SPACE）
UPDATE tenant SET parent_id = 't-team-core'
  WHERE id = 't-space-a' AND parent_id = 't-org-demo';

-- bcrypt("agentflow123", cost 12)
INSERT IGNORE INTO app_user (id, email, password_hash, display_name, platform_role) VALUES
  ('u-admin', 'admin@agentflow.dev', '$2b$12$L/O9.uhIGyula1GE4bDm1.d94fbkK6ALxdCVE2VGmTNKSMAZLrFOG', 'Demo Admin', 'ADMIN'),
  ('u-editor', 'editor@agentflow.dev', '$2b$12$L/O9.uhIGyula1GE4bDm1.d94fbkK6ALxdCVE2VGmTNKSMAZLrFOG', 'Demo Editor', 'USER'),
  ('u-viewer', 'viewer@agentflow.dev', '$2b$12$L/O9.uhIGyula1GE4bDm1.d94fbkK6ALxdCVE2VGmTNKSMAZLrFOG', 'Demo Viewer', 'USER'),
  ('u-beta', 'beta@agentflow.dev', '$2b$12$L/O9.uhIGyula1GE4bDm1.d94fbkK6ALxdCVE2VGmTNKSMAZLrFOG', 'Beta Editor', 'USER'),
  ('u-orgadm', 'orgadmin@agentflow.dev', '$2b$12$L/O9.uhIGyula1GE4bDm1.d94fbkK6ALxdCVE2VGmTNKSMAZLrFOG', 'Org Admin', 'USER');

-- 三级继承验证：u-orgadm 只在 ORG 上是 ADMIN，可继承进入 Core Team 与 Space Alpha/Beta
INSERT IGNORE INTO tenant_member (tenant_id, user_id, role, inherited) VALUES
  ('t-org-demo', 'u-admin', 'OWNER', 0),
  ('t-space-a', 'u-admin', 'OWNER', 0),
  ('t-space-beta', 'u-admin', 'OWNER', 0),
  ('t-space-a', 'u-editor', 'EDITOR', 0),
  ('t-space-a', 'u-viewer', 'VIEWER', 0),
  ('t-space-beta', 'u-beta', 'EDITOR', 0),
  ('t-org-demo', 'u-orgadm', 'ADMIN', 0);

INSERT IGNORE INTO budget_ledger (tenant_id, period, calls_used, tokens_used, limit_calls, limit_tokens)
SELECT id, DATE_FORMAT(NOW(3), '%Y-%m'), 0, 0, 10000, 50000 FROM tenant;
"""

DOC_TITLE = "售后与账号常见问题"
DOC_CHUNKS = [
    "退款政策：购买后七天内可申请全额退款，在订单页提交申请并填写订单编号，审核通过后原路退回。超过七天、三十天内可申请换货或维修。",
    "发票开具：增值税普通发票在订单完成后三个工作日内开出，专票需补充开票资料。逾期未收到可联系财务组补开，电子发票发送至注册邮箱。",
    "账号安全：登录验证码收不到时，先检查短信拦截与手机号是否绑定；连续五次输错密码会锁定三十分钟，可通过绑定邮箱自助解锁。",
]
CHAR_COUNT = sum(len(c) for c in DOC_CHUNKS)

chunk_values = ",\n".join(
    f" ('kc-demo-{i + 1}', 't-space-a', 'kb-demo', 'kd-demo-1', {i}, '{DOC_TITLE}', "
    f"'{c}', {max(1, len(c) // 3)})"
    for i, c in enumerate(DOC_CHUNKS)
)

kb_block = f"""
-- 内置知识库：示例里的 retrieval 节点引用 kb-demo，开箱即有真实文档可检索
INSERT IGNORE INTO knowledge_base (id, tenant_id, name, description, created_by)
VALUES ('kb-demo', 't-space-a', '产品与售后知识库',
        '退款、发票与账号安全说明，供示例工作流检索', 'u-admin');

INSERT INTO knowledge_document
  (id, tenant_id, kb_id, title, source, char_count, chunk_count, created_by)
VALUES ('kd-demo-1', 't-space-a', 'kb-demo', '{DOC_TITLE}', 'TEXT',
        {CHAR_COUNT}, {len(DOC_CHUNKS)}, 'u-admin')
ON DUPLICATE KEY UPDATE title = VALUES(title), chunk_count = VALUES(chunk_count);

INSERT INTO knowledge_chunk
  (id, tenant_id, kb_id, document_id, ordinal, document_title, content, token_count)
VALUES
{chunk_values}
ON DUPLICATE KEY UPDATE content = VALUES(content), document_title = VALUES(document_title);
"""

blocks: list[str] = []
for wf_id, ver_id, name, desc, dsl_file in DEMOS:
    meta = json.loads((ROOT / "scripts" / dsl_file).read_text(encoding="utf-8"))
    sql_json, checksum = sql_literal(meta)
    blocks.append(
        f"""
-- 内置示例工作流「{name.removeprefix('示例：')}」（已发布 v4）
INSERT INTO workflow_definition
  (id, tenant_id, name, description, status, current_version_id, created_by)
VALUES
  ('{wf_id}', 't-space-a', '{name}',
   '{desc}',
   'PUBLISHED', '{ver_id}', 'u-admin')
ON DUPLICATE KEY UPDATE current_version_id = VALUES(current_version_id),
                         name = VALUES(name), description = VALUES(description);

INSERT INTO workflow_version
  (id, definition_id, version_no, dsl_json, dsl_checksum, immutable, published_at, published_by, created_by)
VALUES
  ('{ver_id}', '{wf_id}', 4, '{sql_json}', '{checksum}', 1, NOW(3), 'u-admin', 'u-admin')
ON DUPLICATE KEY UPDATE id = id;
"""
    )

out = header + kb_block + "".join(blocks)
(ROOT / "scripts" / "seed-demo.sql").write_text(out, encoding="utf-8", newline="\n")
print("seed-demo.sql regenerated,", len(out), "bytes,", len(DEMOS), "demo workflows")
