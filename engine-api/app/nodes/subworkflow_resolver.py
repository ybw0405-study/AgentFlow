"""子工作流 DSL 解析器：引擎经 gateway 内部端点查询目标工作流的已发布快照。

信任边界（§5.2）：Python 不读业务库，只经 gateway（唯一入口）取不可变版本快照；
携带服务间令牌；空间隔离由控制面校验，跨租户返回 404。
"""

from __future__ import annotations

from typing import Any, Protocol

import httpx

from ..errors import PermanentNodeError


class SubworkflowResolver(Protocol):
    async def resolve(
        self, *, definition_id: str, space_id: str, version_id: str | None
    ) -> dict[str, Any]: ...


class GatewaySubworkflowResolver:
    def __init__(self, base_url: str, internal_token: str, timeout_s: float = 10.0) -> None:
        self._client = httpx.AsyncClient(base_url=base_url, timeout=timeout_s)
        self._headers = {"X-Internal-Token": internal_token}

    async def resolve(
        self, *, definition_id: str, space_id: str, version_id: str | None
    ) -> dict[str, Any]:
        params: dict[str, str] = {"spaceId": space_id}
        if version_id is not None:
            params["versionId"] = version_id
        try:
            resp = await self._client.get(
                f"/internal/workflows/{definition_id}/dsl",
                params=params,
                headers=self._headers,
            )
        except httpx.HTTPError as exc:
            raise PermanentNodeError(
                "SUBWF-UNAVAILABLE-001", f"control plane unreachable: {exc}"
            ) from exc
        if resp.status_code == 404:
            raise PermanentNodeError("SUBWF-NOT-FOUND-001", "subworkflow definition not found")
        if resp.status_code != 200:
            raise PermanentNodeError(
                "SUBWF-RESOLVE-001", f"control plane returned {resp.status_code}"
            )
        body = resp.json()
        return body["data"]["dsl"]

    async def aclose(self) -> None:
        await self._client.aclose()
