"""文档检索的内部客户端：引擎 → gateway → workflow-service。

信任边界与 connector 路径一致：Python 侧只持空间 id 与内部令牌，文档内容始终
存放在控制面；跨租户的知识库 id 会被服务端直接忽略，引擎无法借此越权。
"""

from __future__ import annotations

import time
from typing import Any

import httpx

from ...errors import PermanentNodeError, TransientNodeError

_SEARCH_PATH = "/internal/knowledge/search"


class KnowledgeSearchClient:
    """调用控制面的 BM25 检索端点，返回真实的文档切片而非构造文本。"""

    def __init__(
        self,
        base_url: str,
        internal_token: str,
        timeout_s: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._client = httpx.AsyncClient(base_url=base_url, timeout=timeout_s, transport=transport)
        self._headers = {"X-Internal-Token": internal_token}

    async def search(
        self,
        *,
        tenant_id: str,
        knowledge_base_ids: list[str],
        query: str,
        top_k: int,
        min_score: float,
        trace_id: str = "",
    ) -> dict[str, Any]:
        started = time.monotonic()
        body = {
            "tenantId": tenant_id,
            "spaceId": tenant_id,
            "knowledgeBaseIds": knowledge_base_ids,
            "query": query,
            "topK": top_k,
            "minScore": min_score,
        }
        try:
            resp = await self._client.post(
                _SEARCH_PATH, json=body, headers={**self._headers, "X-Trace-Id": trace_id}
            )
        except httpx.TimeoutException as exc:
            raise TransientNodeError(
                "RETRIEVAL-TIMEOUT-001",
                f"knowledge search timed out after {(time.monotonic() - started) * 1000:.0f}ms",
            ) from exc
        except httpx.HTTPError as exc:
            raise TransientNodeError(
                "RETRIEVAL-UPSTREAM-001", "knowledge service unreachable"
            ) from exc

        if resp.status_code in (401, 403, 404):
            raise PermanentNodeError(
                "RETRIEVAL-NOT_FOUND-001", "knowledge service rejected the internal call"
            )
        if resp.status_code != 200:
            raise TransientNodeError(
                "RETRIEVAL-UPSTREAM-001", f"knowledge service returned HTTP {resp.status_code}"
            )
        try:
            payload = resp.json()
            data = payload["data"]
        except (ValueError, KeyError, TypeError) as exc:
            raise PermanentNodeError(
                "RETRIEVAL-RESPONSE-001", "malformed knowledge search response"
            ) from exc
        if not isinstance(data, dict) or not isinstance(data.get("results"), list):
            raise PermanentNodeError(
                "RETRIEVAL-RESPONSE-001", "malformed knowledge search response"
            )
        return data

    async def aclose(self) -> None:
        await self._client.aclose()
