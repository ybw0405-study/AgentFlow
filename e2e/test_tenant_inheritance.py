"""P2-E2E：三级租户继承 —— ORG 成员可进入子 SPACE，非成员仍是 404。"""

from __future__ import annotations

import uuid

from .conftest import SPACE_A, SPACE_B, headers, login

ORG_ADMIN_EMAIL = "orgadmin@agentflow.dev"


def test_org_member_inherits_into_team_and_space(client):
    token = login(client, ORG_ADMIN_EMAIL)

    # Space Alpha 挂在 Core Team 下（ORG → TEAM → SPACE 两层祖先）
    resp = client.get("/api/v1/workflows", headers=headers(token, SPACE_A))
    assert resp.status_code == 200, resp.text

    # 另一个 Space 直接从 ORG 派生，同样可继承
    resp = client.get("/api/v1/workflows", headers=headers(token, SPACE_B))
    assert resp.status_code == 200, resp.text


def test_inherited_admin_can_publish(client, admin_token):
    """继承来的 ADMIN 具备 ADMIN 级能力（新建 + 列表），不需要显式空间成员关系。"""
    token = login(client, ORG_ADMIN_EMAIL)
    resp = client.post("/api/v1/workflows",
                       json={"name": f"inherited-{uuid.uuid4().hex[:8]}"},
                       headers=headers(token, SPACE_A))
    assert resp.status_code == 200, resp.text


def test_non_member_still_404(client, beta_token):
    """beta 用户不在 Alpha 链上：跨租户仍是 404，不因继承放开隔离。"""
    resp = client.get("/api/v1/workflows", headers=headers(beta_token, SPACE_A))
    assert resp.status_code == 404, resp.text
    assert resp.json()["code"] == "WF-NOT_FOUND-001"


def test_unknown_space_is_404(client, admin_token):
    resp = client.get("/api/v1/workflows", headers=headers(admin_token, "t-does-not-exist"))
    assert resp.status_code == 404, resp.text


def test_me_lists_inherited_spaces_for_space_switcher(client):
    """前端空间选择器依赖 /auth/me：ORG 成员必须能看到继承来的 SPACE。"""
    token = login(client, ORG_ADMIN_EMAIL)
    resp = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200, resp.text
    spaces = {m["tenantId"]: m["role"] for m in resp.json()["data"]["memberships"]}
    assert SPACE_A in spaces and SPACE_B in spaces
    assert spaces[SPACE_A] == "ADMIN"      # 继承时 OWNER→ADMIN，显式 ADMIN 保持
