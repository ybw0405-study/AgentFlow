"""OpenAI 兼容 /embeddings provider。

错误分类与 openai_compatible 同口径：401/403/400/404/422 永久，429/5xx/超时瞬时；
provider 不做重试，重试由引擎 RetryPolicy 统一执行，避免双层重试重复计费。
"""

from __future__ import annotations

from typing import Protocol

import httpx

from ..errors import PermanentNodeError, TransientNodeError


class EmbeddingProvider(Protocol):
    async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]: ...


class OpenAIEmbeddingProvider:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        dimensions: int = 1024,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self.model = model
        self.dimensions = dimensions
        self._client = client if client is not None else httpx.AsyncClient()
        self._owns_client = client is None

    @classmethod
    def from_settings(cls, settings) -> OpenAIEmbeddingProvider | None:
        if not settings.embedding_base_url or not settings.embedding_api_key:
            return None
        return cls(
            settings.embedding_base_url,
            settings.embedding_api_key,
            settings.embedding_model,
            settings.embedding_dimensions,
        )

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
        if not texts:
            return []
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        try:
            response = await self._client.post(
                f"{self._base_url}/embeddings",
                json={"model": self.model, "input": texts},
                headers=headers,
                timeout=httpx.Timeout(max(0.1, timeout_s), connect=min(3.0, max(0.1, timeout_s))),
            )
        except httpx.TimeoutException as exc:
            raise TransientNodeError("EMBED-TIMEOUT-001", "embedding request timed out") from exc
        except httpx.HTTPError as exc:
            raise TransientNodeError("EMBED-UPSTREAM-001", "embedding request failed") from exc
        if response.status_code in (401, 403):
            raise PermanentNodeError("EMBED-AUTH-001", "embedding endpoint rejected credentials")
        if response.status_code in (400, 404, 422):
            raise PermanentNodeError("EMBED-REQUEST-001", "embedding request rejected")
        if response.status_code >= 400:
            raise TransientNodeError(
                "EMBED-UPSTREAM-001", f"embedding endpoint {response.status_code}"
            )
        try:
            body = response.json()
            data = sorted(body["data"], key=lambda item: item["index"])
            vectors = [item["embedding"] for item in data]
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise PermanentNodeError("EMBED-RESPONSE-001", "embedding response malformed") from exc
        if len(vectors) != len(texts):
            raise PermanentNodeError("EMBED-RESPONSE-001", "embedding count mismatch")
        for vector in vectors:
            if not isinstance(vector, list) or len(vector) != self.dimensions:
                raise PermanentNodeError("EMBED-RESPONSE-001", "embedding dimensions mismatch")
        return vectors
