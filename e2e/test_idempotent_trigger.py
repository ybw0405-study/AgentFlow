"""E2E-4（US-02 并发触发）：同一 (tenant, idempotencyKey) 并发 10 次 → 恰好 1 个执行。"""

from __future__ import annotations

import uuid
from concurrent.futures import ThreadPoolExecutor

from .conftest import DEMO_DSL, SPACE_A, headers, new_idem, wait_terminal
from .test_cross_tenant_404 import _create_published_workflow


def test_concurrent_idempotent_trigger(client, editor_token, admin_token):
    wf_id = _create_published_workflow(client, editor_token, SPACE_A, admin_token)
    idem = new_idem()

    def trigger(_: int) -> dict:
        resp = client.post(f"/api/v1/workflows/{wf_id}/executions",
                           json={"idempotencyKey": idem, "variables": {"question": "并发测试"}},
                           headers=headers(editor_token, SPACE_A))
        assert resp.status_code == 200, resp.text
        return resp.json()["data"]

    with ThreadPoolExecutor(max_workers=10) as pool:
        results = list(pool.map(trigger, range(10)))

    ids = {r["executionId"] for r in results}
    assert len(ids) == 1, f"expected exactly one execution, got {ids}"
    assert sum(0 if r["idempotentReplay"] else 1 for r in results) == 1

    detail = wait_terminal(client, editor_token, SPACE_A, ids.pop())
    assert detail["execution"]["status"] == "SUCCEEDED"
