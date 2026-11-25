"""OpenAI 兼容 /rerank provider：对检索候选做精排，只决定名次，不改命中分数。

端点形态取 Cohere/Jina 通用约定（多数 OpenAI 兼容网关与自建 rerank 服务同款）：
    POST {base_url}/rerank
    {"model": ..., "query": ..., "documents": [...], "top_n": N}
    -> {"results": [{"index": int, "relevance_score": float}]}

错误分类与 embedding/llm 同口径：401/403/400/404/422 永久，429/5xx/超时瞬时；
provider 不做重试，重试由引擎 RetryPolicy 统一执行，避免双层重试重复计费。
"""

from __future__ import annotations

import math
from typing import Protocol

import httpx

from ..errors import PermanentNodeError, TransientNodeError


class RerankProvider(Protocol):
    model: str

    async def rerank(
        self, query: str, documents: list[str], timeout_s: float
    ) -> list[tuple[int, float]]:
        """返回 (原下标, 相关性分) 按相关性降序。provider 只给名次，调用方不得拿分当相似度。"""
        ...


class OpenAIRerankProvider:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self.model = model
        self._client = client if client is not None else httpx.AsyncClient()
        self._owns_client = client is None

    @classmethod
    def from_settings(cls, settings) -> OpenAIRerankProvider | None:
        if not settings.rerank_base_url or not settings.rerank_api_key:
            return None
        return cls(settings.rerank_base_url, settings.rerank_api_key, settings.rerank_model)

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def rerank(
        self, query: str, documents: list[str], timeout_s: float
    ) -> list[tuple[int, float]]:
        if not documents:
            return []
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        try:
            response = await self._client.post(
                f"{self._base_url}/rerank",
                json={
                    "model": self.model,
                    "query": query,
                    "documents": documents,
                    "top_n": len(documents),
                },
                headers=headers,
                timeout=httpx.Timeout(max(0.1, timeout_s), connect=min(3.0, max(0.1, timeout_s))),
            )
        except httpx.TimeoutException as exc:
            raise TransientNodeError("RERANK-TIMEOUT-001", "rerank request timed out") from exc
        except httpx.HTTPError as exc:
            raise TransientNodeError("RERANK-UPSTREAM-001", "rerank request failed") from exc
        if response.status_code in (401, 403):
            raise PermanentNodeError("RERANK-AUTH-001", "rerank endpoint rejected credentials")
        if response.status_code in (400, 404, 422):
            raise PermanentNodeError("RERANK-REQUEST-001", "rerank request rejected")
        if response.status_code >= 400:
            raise TransientNodeError(
                "RERANK-UPSTREAM-001", f"rerank endpoint {response.status_code}"
            )
        try:
            body = response.json()
            raw = body["results"]
            items = sorted(
                ((int(row["index"]), float(row["relevance_score"])) for row in raw),
                key=lambda item: (-item[1], item[0]),
            )
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise PermanentNodeError("RERANK-RESPONSE-001", "rerank response malformed") from exc
        if len({index for index, _ in items}) != len(items):
            raise PermanentNodeError("RERANK-RESPONSE-001", "rerank response has duplicate indexes")
        if any(index < 0 or index >= len(documents) for index, _ in items):
            raise PermanentNodeError("RERANK-RESPONSE-001", "rerank response has an out-of-range index")
        if any(not math.isfinite(score) for _, score in items):
            raise PermanentNodeError("RERANK-RESPONSE-001", "rerank response has a non-finite score")
        return items
