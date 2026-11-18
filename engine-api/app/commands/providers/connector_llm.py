"""经连接器服务调用的 LLM Provider（画布里的「模型连接」）。

信任边界（§5.2）与 tool 节点一致：引擎只持有连接 id 与内部令牌，端点地址和 API Key
全部留在 connector-service，由它解密后注入请求头、按白名单出网。Python 侧既拿不到明文
密钥，也无法把请求指向别的主机——画布配置里没有 URL 字段，只有连接 id。

为什么不直连：一条连接可以被多个工作流、多个空间复用，密钥只存一份密文；改密钥时
不必重启引擎。代价是多一跳内网调用，且请求/响应要经过连接器服务的 256KB 裁剪。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

import httpx

from ...governance.tokenmeter import estimate_tokens
from ..base import CommandResult
from .openai_compatible import build_chat_payload, extract_text, int_or

logger = logging.getLogger(__name__)

_MAX_LOG_DETAIL = 200

# 连接器错误码 → LLM 语义。执行详情里应当看到「模型认证失败」，而不是连接器的内部编码；
# 顺带把「配置类错误」和「网络瞬时错误」分开，后者才允许 RetryPolicy 重试。
_ERROR_MAP: dict[str, tuple[str, bool]] = {
    "CONN-NOT_FOUND-001": ("LLM-CONNECTION-001", False),
    "CONN-DISABLED-001": ("LLM-CONNECTION-001", False),
    "CONN-SANDBOX-001": ("LLM-CONNECTION-001", False),
    "CONN-CONFIG-001": ("LLM-CONNECTION-001", False),
    "CONN-CREDENTIAL-001": ("LLM-CREDENTIAL-001", False),
    "CONN-CREDENTIAL-002": ("LLM-CREDENTIAL-001", False),
    "CONN-CREDENTIAL-EXPIRED": ("LLM-CREDENTIAL-001", False),
    "CONN-HTTP-4XX": ("LLM-REQUEST-001", False),
    "CONN-REDIRECT-001": ("LLM-UPSTREAM-001", False),
    "CONN-RESPONSE-TOO_LARGE": ("LLM-RESPONSE-001", False),
    "CONN-HTTP-5XX": ("LLM-UPSTREAM-001", True),
    "CONN-TIMEOUT": ("LLM-TIMEOUT-001", True),
    "CONN-INFLIGHT": ("LLM-UPSTREAM-001", True),
    "CONN-UPSTREAM-001": ("LLM-UPSTREAM-001", True),
}


def map_connector_error(code: str, transient: bool) -> tuple[str, bool]:
    """连接器错误码转成模型侧错误码；未收录的保持原值并沿用上游的瞬时判定。"""
    return _ERROR_MAP.get(code, (code or "LLM-UPSTREAM-001", transient))


class ConnectorLlmProvider:
    """把 llm 节点的调用代理到 connector-service 的 chat 操作。"""

    name = "connector"

    def __init__(
        self,
        *,
        gateway_base_url: str,
        internal_token: str,
        connector_id: str,
        default_model: str = "",
        timeout_s: float = 60.0,
        max_response_chars: int = 100_000,
        json_response_format: bool = True,
        send_seed: bool = False,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.connector_id = connector_id
        self._base_url = gateway_base_url.rstrip("/")
        self._default_model = default_model
        self._timeout_s = timeout_s
        self._max_response_chars = max_response_chars
        self._json_response_format = json_response_format
        self._send_seed = send_seed
        self._headers = {"X-Internal-Token": internal_token}
        self._client = httpx.AsyncClient(
            base_url=self._base_url,
            timeout=timeout_s + 5.0,
            transport=transport,
            limits=httpx.Limits(max_connections=20, max_keepalive_connections=10),
        )

    @property
    def configured(self) -> bool:
        """可用性由连接器服务判定：连接是否启用、凭据是否配齐，都在那边。"""
        return True

    def _resolve_model(self, model: str) -> str:
        """历史草稿里残留的 mock-* 占位名不能在真实端点上下发，回落到连接声明的默认模型。"""
        if (not model or model.lower().startswith("mock")) and self._default_model:
            return self._default_model
        return model

    def _failure(
        self, code: str, transient: bool, started: float, detail: str = ""
    ) -> CommandResult:
        if detail:
            logger.warning(
                "connector llm failure connection=%s code=%s detail=%s",
                self.connector_id,
                code,
                detail[:_MAX_LOG_DETAIL],
            )
        return CommandResult(
            ok=False, error_code=code, transient=transient, latency_ms=self._ms(started)
        )

    @staticmethod
    def _ms(started: float) -> int:
        return int((time.monotonic() - started) * 1000)

    async def complete(
        self,
        *,
        model: str,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.7,
        max_tokens: int = 1024,
        output_mode: str = "text",
        output_schema: dict[str, Any] | None = None,
        seed: str,
        trace_id: str,
        call_id: str = "",
        space_id: str = "",
    ) -> CommandResult:
        started = time.monotonic()
        resolved_model = self._resolve_model(model)
        payload = build_chat_payload(
            model=resolved_model,
            prompt=prompt,
            system=system,
            temperature=temperature,
            max_tokens=max_tokens,
            output_mode=output_mode,
            output_schema=output_schema,
            json_response_format=self._json_response_format,
            seed_value=int(seed[:8], 16) % (2**31) if self._send_seed else None,
        )
        request = {
            "tenantId": space_id,
            # callId 用引擎派生的确定性值：崩溃重放命中连接器的幂等缓存，避免重复计费
            "callId": call_id or f"llm:{self.connector_id}:{trace_id}",
            "operation": "chat",
            "params": payload,
            "timeoutMs": int(self._timeout_s * 1000),
            "traceId": trace_id,
        }
        try:
            resp = await self._client.post(
                f"/internal/connectors/{self.connector_id}/invoke",
                json=request,
                headers=self._headers,
            )
        except httpx.TimeoutException:
            return self._failure("LLM-TIMEOUT-001", True, started, "gateway timeout")
        except httpx.HTTPError as exc:
            return self._failure("LLM-UPSTREAM-001", True, started, type(exc).__name__)

        if resp.status_code == 404:
            return self._failure("LLM-CONNECTION-001", False, started, "connection not found")
        if resp.status_code != 200:
            return self._failure(
                "LLM-UPSTREAM-001", resp.status_code >= 500, started, f"status={resp.status_code}"
            )
        try:
            envelope = resp.json()
        except (ValueError, UnicodeDecodeError):
            return self._failure("LLM-RESPONSE-001", False, started, "envelope not json")
        if not isinstance(envelope, dict):
            return self._failure("LLM-RESPONSE-001", False, started, "envelope not object")

        if not envelope.get("ok"):
            code, transient = map_connector_error(
                str(envelope.get("errorCode") or ""), bool(envelope.get("transientError"))
            )
            return self._failure(code, transient, started, str(envelope.get("errorCode") or ""))

        data = envelope.get("data")
        if not isinstance(data, dict):
            return self._failure("LLM-RESPONSE-001", False, started, "data is not an object")
        if data.get("raw") is not None:
            # 连接器把非 JSON 响应包成 {"raw": ...}，说明上游没按 chat completions 回应
            return self._failure("LLM-RESPONSE-001", False, started, "upstream returned non-json")

        text = extract_text(data)
        if not text:
            return self._failure("LLM-RESPONSE-001", False, started, "empty content")
        if len(text) > self._max_response_chars:
            return self._failure(
                "LLM-RESPONSE-001", False, started, "content exceeds AF_LLM_MAX_RESPONSE_CHARS"
            )

        parsed: dict[str, Any] | list[Any] | None = None
        if output_mode == "json":
            try:
                parsed = json.loads(text)
            except ValueError as exc:
                return self._failure("LLM-RESPONSE-001", False, started, f"invalid json: {exc}")

        usage_raw = data.get("usage") if isinstance(data.get("usage"), dict) else {}
        fallback_prompt = estimate_tokens(prompt + (system or ""))
        prompt_tokens = int_or(usage_raw.get("prompt_tokens"), fallback_prompt)
        completion_tokens = int_or(usage_raw.get("completion_tokens"), estimate_tokens(text))
        total_tokens = int_or(usage_raw.get("total_tokens"), prompt_tokens + completion_tokens)

        return CommandResult(
            ok=True,
            data={"text": text, "parsed": parsed},
            token_usage=total_tokens,
            latency_ms=self._ms(started),
            extra={
                "usage": {
                    "prompt": prompt_tokens,
                    "completion": completion_tokens,
                    "total": total_tokens,
                },
                "provider": self.name,
                "model": resolved_model,
                "connectionId": self.connector_id,
            },
        )

    async def aclose(self) -> None:
        await self._client.aclose()


class ConnectorLlmFactory:
    """按连接 id 复用 provider，避免每个节点调用都新建连接池。"""

    def __init__(
        self,
        *,
        gateway_base_url: str,
        internal_token: str,
        timeout_s: float = 60.0,
        max_response_chars: int = 100_000,
        json_response_format: bool = True,
        send_seed: bool = False,
    ) -> None:
        self._base_url = gateway_base_url
        self._internal_token = internal_token
        self._timeout_s = timeout_s
        self._max_response_chars = max_response_chars
        self._json_response_format = json_response_format
        self._send_seed = send_seed
        self._cache: dict[str, ConnectorLlmProvider] = {}

    def for_connection(self, connector_id: str) -> ConnectorLlmProvider:
        provider = self._cache.get(connector_id)
        if provider is None:
            provider = ConnectorLlmProvider(
                gateway_base_url=self._base_url,
                internal_token=self._internal_token,
                connector_id=connector_id,
                timeout_s=self._timeout_s,
                max_response_chars=self._max_response_chars,
                json_response_format=self._json_response_format,
                send_seed=self._send_seed,
            )
            self._cache[connector_id] = provider
        return provider

    async def aclose(self) -> None:
        for provider in self._cache.values():
            await provider.aclose()
        self._cache.clear()
