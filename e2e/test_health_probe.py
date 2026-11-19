"""E2E（运维探针）：/health 必须无凭据可访问，且如实反映依赖状态。

这个端点给 docker-compose / k8s / CI 用：一旦被认证或限流过滤器拦下，
基础设施探活就会拿到 401，容器永远起不来。同时校验它的语义——
依赖全好返回 UP，任何一个依赖不可用返回 503 DEGRADED。
"""

from __future__ import annotations

from .conftest import SPACE_A, headers


def test_health_is_public_and_reports_dependencies(client, admin_token):
    # 不带任何 token 也能拿到（探针不能依赖凭据）
    resp = client.get("/health")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["status"] == "UP"
    assert body["service"] == "gateway"
    assert [c["name"] for c in body["checks"]] == ["redis"]
    assert all(c["up"] for c in body["checks"])

    # 也不能被限流/身份层改写：带 token 访问结果一致
    resp = client.get("/health", headers=headers(admin_token, SPACE_A))
    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "UP"
