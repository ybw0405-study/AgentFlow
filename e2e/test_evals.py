"""P3-E2E：评测集跑分 + A/B 版本对比。

评测执行走真实引擎；A/B 在「同一数据集、同一 DSL」下只改 versionId，
因此比较的是版本差异而非执行通道差异。
"""

from __future__ import annotations

import copy
import json
import time
import uuid

from .conftest import DEMO_DSL, SPACE_A, headers, new_idem


def _create_dataset(client, token, name: str) -> str:
    resp = client.post("/api/v1/evals/datasets", json={"name": name},
                       headers=headers(token, SPACE_A))
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]["dataset"]["id"]


def _add_case(client, token, dataset_id: str, name: str, assertion: dict,
              expected: object | None = None) -> None:
    body: dict = {
        "name": name,
        "input": {"question": f"{name} 的问题"},
        "assertion": assertion,
    }
    if expected is not None:
        body["expected"] = expected
    resp = client.post(f"/api/v1/evals/datasets/{dataset_id}/cases", json=body,
                       headers=headers(token, SPACE_A))
    assert resp.status_code == 200, resp.text


def _publish_workflow(client, editor_token, admin_token, dsl: dict) -> tuple[str, str]:
    wf = client.post("/api/v1/workflows", headers=headers(editor_token, SPACE_A),
                     json={"name": f"e2e-eval-{uuid.uuid4().hex[:8]}"})
    assert wf.status_code == 200, wf.text
    wf_id = wf.json()["data"]["id"]

    def _save_and_publish(payload: dict) -> str:
        assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=payload,
                          headers=headers(editor_token, SPACE_A)).status_code == 200
        version = client.post(f"/api/v1/workflows/{wf_id}/versions",
                              headers=headers(editor_token, SPACE_A))
        assert version.status_code == 200, version.text
        version_id = version.json()["data"]["id"]
        assert client.post(f"/api/v1/workflows/{wf_id}/publish",
                           json={"versionId": version_id},
                           headers=headers(admin_token, SPACE_A)).status_code == 200
        return version_id

    return wf_id, _save_and_publish(dsl)


def _wait_run(client, token, run_id: str, timeout_s: float = 90.0) -> dict:
    """等到评测运行真正收口。

    只把 SUCCEEDED / FAILED / CANCELLED 当终态：run 行是插入即 RUNNING，
    但用「!= RUNNING 就算完」会让中间态（或异常写入）也蒙混过关。
    """
    terminal = {"SUCCEEDED", "FAILED", "CANCELLED"}
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        resp = client.get(f"/api/v1/evals/runs/{run_id}", headers=headers(token, SPACE_A))
        assert resp.status_code == 200, resp.text
        detail = resp.json()["data"]
        if detail["run"]["status"] in terminal:
            return detail
        time.sleep(1.0)
    raise AssertionError(f"eval run {run_id} did not finish in {timeout_s}s")


def test_eval_run_scores_each_case(client, editor_token, admin_token):
    dataset_id = _create_dataset(client, editor_token, f"e2e-eval-{uuid.uuid4().hex[:6]}")
    _add_case(client, editor_token, dataset_id, "has-answer",
              {"mode": "EXISTS", "path": "answer"})
    _add_case(client, editor_token, dataset_id, "missing-field",
              {"mode": "EXISTS", "path": "not_there"})

    wf_id, version_id = _publish_workflow(client, editor_token, admin_token, DEMO_DSL)
    started = client.post(f"/api/v1/evals/datasets/{dataset_id}/runs",
                          json={"definitionId": wf_id, "versionId": version_id},
                          headers=headers(editor_token, SPACE_A))
    assert started.status_code == 200, started.text
    run_id = started.json()["data"]["run"]["id"]

    detail = _wait_run(client, editor_token, run_id)
    run = detail["run"]
    assert run["total"] == 2
    assert run["passed"] == 1
    assert run["failed"] == 1
    assert run["status"] == "SUCCEEDED"

    results = {r["caseName"]: r for r in detail["results"]}
    assert results["has-answer"]["status"] == "PASSED"
    assert results["missing-field"]["status"] == "FAILED"
    assert "not_there" in results["missing-field"]["errorMessage"]
    assert results["has-answer"]["executionId"]
    assert results["has-answer"]["actual"] is not None


def test_ab_experiment_compares_two_versions(client, editor_token, admin_token):
    dataset_id = _create_dataset(client, editor_token, f"e2e-ab-{uuid.uuid4().hex[:6]}")
    _add_case(client, editor_token, dataset_id, "case-1",
              {"mode": "EXISTS", "path": "answer"})
    _add_case(client, editor_token, dataset_id, "case-2",
              {"mode": "EXISTS", "path": "answer"})

    wf = client.post("/api/v1/workflows", headers=headers(editor_token, SPACE_A),
                     json={"name": f"e2e-ab-{uuid.uuid4().hex[:8]}"})
    wf_id = wf.json()["data"]["id"]

    def _publish(payload: dict) -> str:
        assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=payload,
                          headers=headers(editor_token, SPACE_A)).status_code == 200
        version = client.post(f"/api/v1/workflows/{wf_id}/versions",
                              headers=headers(editor_token, SPACE_A))
        assert version.status_code == 200, version.text
        version_id = version.json()["data"]["id"]
        assert client.post(f"/api/v1/workflows/{wf_id}/publish",
                           json={"versionId": version_id},
                           headers=headers(admin_token, SPACE_A)).status_code == 200
        return version_id

    version_a = _publish(DEMO_DSL)
    tweaked = copy.deepcopy(DEMO_DSL)
    tweaked["nodes"][3]["config"]["maxTokens"] = 512
    version_b = _publish(tweaked)

    started = client.post("/api/v1/evals/experiments", json={
        "definitionId": wf_id,
        "datasetId": dataset_id,
        "name": f"ab-{uuid.uuid4().hex[:6]}",
        "versionA": version_a,
        "versionB": version_b,
    }, headers=headers(editor_token, SPACE_A))
    assert started.status_code == 200, started.text
    experiment_id = started.json()["data"]["experiment"]["id"]

    deadline = time.monotonic() + 150
    detail: dict = {}
    while time.monotonic() < deadline:
        resp = client.get(f"/api/v1/evals/experiments/{experiment_id}",
                          headers=headers(editor_token, SPACE_A))
        assert resp.status_code == 200, resp.text
        detail = resp.json()["data"]
        if detail["experiment"]["status"] == "DONE":
            break
        time.sleep(1.5)
    assert detail["experiment"]["status"] == "DONE", detail

    summary = json.loads(detail["experiment"]["summaryJson"])
    assert summary["passRateA"] == 1.0 and summary["passRateB"] == 1.0
    assert summary["winner"] in ("A", "B", "TIE")
    assert detail["runA"]["total"] == 2 and detail["runB"]["total"] == 2
    assert detail["statsA"]["passed"] == 2 and detail["statsB"]["passed"] == 2
