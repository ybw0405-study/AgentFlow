"""E2E：跨租户访问一律 404，不泄露资源存在性；VIEWER 触发 403。"""

from __future__ import annotations

import uuid

from .conftest import DEMO_DSL, SPACE_A, SPACE_B, headers, new_idem


def _create_published_workflow(client, token: str, space: str, admin_token: str) -> str:
    """编辑器建/存/冻结；发布须 ADMIN（§4.1），用 admin_token。"""
    hdrs = headers(token, space)
    resp = client.post("/api/v1/workflows",
                       json={"name": f"e2e-tenant-{uuid.uuid4().hex[:8]}"}, headers=hdrs)
    wf_id = resp.json()["data"]["id"]
    resp = client.put(f"/api/v1/workflows/{wf_id}/draft", json=DEMO_DSL, headers=hdrs)
    assert resp.status_code == 200, resp.text
    resp = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=hdrs)
    assert resp.status_code == 200, resp.text
    version_id = resp.json()["data"]["id"]
    resp = client.post(f"/api/v1/workflows/{wf_id}/publish",
                       json={"versionId": version_id},
                       headers=headers(admin_token, space))
    assert resp.status_code == 200, resp.text
    return wf_id


def test_cross_tenant_access_is_404(client, editor_token, beta_token, admin_token):
    wf_id = _create_published_workflow(client, editor_token, SPACE_A, admin_token)

    # Space B 的编辑器拿 Space A 的 definitionId：一律 404
    beta = headers(beta_token, SPACE_B)
    resp = client.get(f"/api/v1/workflows/{wf_id}", headers=beta)
    assert resp.status_code == 404
    assert resp.json()["code"] == "WF-NOT_FOUND-001"
    # 不泄露资源内容（工作流名不得出现在响应中）
    assert "e2e-tenant" not in resp.text

    resp = client.post(f"/api/v1/workflows/{wf_id}/executions",
                       json={"idempotencyKey": new_idem(), "variables": {}}, headers=beta)
    assert resp.status_code == 404

    # Space B 的列表里也看不到
    resp = client.get("/api/v1/workflows", headers=beta)
    assert all(w["id"] != wf_id for w in resp.json()["data"]["items"])


def test_viewer_cannot_trigger(client, editor_token, viewer_token, admin_token):
    wf_id = _create_published_workflow(client, editor_token, SPACE_A, admin_token)
    resp = client.post(f"/api/v1/workflows/{wf_id}/executions",
                       json={"idempotencyKey": new_idem(), "variables": {}},
                       headers=headers(viewer_token, SPACE_A))
    assert resp.status_code == 403
    assert resp.json()["code"] == "WF-FORBIDDEN-001"
