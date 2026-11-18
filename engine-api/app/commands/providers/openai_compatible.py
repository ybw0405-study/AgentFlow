"""OpenAI 兼容 Chat Completions Provider（真实模型）。

信任边界：
- 端点与密钥只来自环境变量（AF_LLM_*），绝不来自 DSL / 用户入参，避免 SSRF 与凭据注入。
- 密钥只在 Authorization 头出现；日志、错误码、事件载荷中一律脱敏。
- 不重试：重试由引擎 RetryPolicy 统一治理（§8.4），避免双层重试导致重复计费；
  provider 只负责把上游状态精确分类为 transient / permanent，让引擎决定是否重放。

崩溃恢复语义：与内置 mock 不同，真实模型调用是外部副作用且非确定性，重放时可能重复计费
（at-least-once）。可通过 AF_LLM_SEND_SEED=true 向上游传递确定性 seed（仅部分厂商支持）。
"""

from __future__ import annotations

import json
import logging
import time
from typing import TYPE_CHECKING, Any

import httpx

from ...governance.tokenmeter import estimate_tokens
from ..base import CommandResult

if TYPE_CHECKING:
    from ...config import Settings

logger = logging.getLogger(__name__)

_CHAT_COMPLETIONS_PATH = "/chat/completions"
_MAX_LOG_DETAIL = 200
_JSON_SYSTEM_HINT = "Return a valid JSON object only, with no markdown fences or extra text."


def _int_or(value: Any, fallback: int) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return fallback
    return max(0, int(value))


class OpenAICompatibleProvider:
    """把 llm 节点的 Command 发到任意 OpenAI 兼容的 /chat/completions 端点。"""

    name = "openai-compatible"

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        default_model: str,
        timeout_s: float = 60.0,
        max_response_chars: int = 100_000,
        json_response_format: bool = True,
        send_seed: bool = False,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url = (base_url or "").rstrip("/")
        # 允许两种写法：.../v1（自动补 /chat/completions）或完整的 .../v1/chat/completions
        self._endpoint = (
            self._base_url
            if self._base_url.endswith(_CHAT_COMPLETIONS_PATH)
            else (self._base_url + _CHAT_COMPLETIONS_PATH if self._base_url else "")
        )
        self._api_key = api_key or ""
        self._default_model = default_model
        self._timeout_s = timeout_s
        self._max_response_chars = max_response_chars
        self._json_response_format = json_response_format
        self._send_seed = send_seed
        # 端点用绝对 URL 传，不依赖 base_url 合并语义，避免 /v1 前缀被覆盖。
        self._client = httpx.AsyncClient(
            timeout=timeout_s,
            transport=transport,
            limits=httpx.Limits(max_connections=20, max_keepalive_connections=10),
        )

    @classmethod
    def from_settings(cls, settings: Settings) -> OpenAICompatibleProvider:
        return cls(
            base_url=settings.llm_base_url,
            api_key=settings.llm_api_key,
            default_model=settings.llm_model,
            timeout_s=settings.llm_timeout_s,
            max_response_chars=settings.llm_max_response_chars,
            json_response_format=settings.llm_json_response_format,
            send_seed=settings.llm_send_seed,
        )

    @property
    def configured(self) -> bool:
        return bool(self._base_url and self._api_key)

    def _resolve_model(self, model: str) -> str:
        """画布从 mock 切到真实 provider 时，沿用 mock-* 占位模型名会 400，回落到默认模型。"""
        if not model or model.lower().startswith("mock"):
            return self._default_model
        return model

    def _redact(self, text: str) -> str:
        safe = text.replace(self._api_key, "***") if self._api_key else text
        return safe[:_MAX_LOG_DETAIL]

    @staticmethod
    def _ms(started: float) -> int:
        return int((time.monotonic() - started) * 1000)

    def _failure(
        self, code: str, transient: bool, started: float, detail: str = ""
    ) -> CommandResult:
        if detail:
            logger.warning(
                "llm provider failure code=%s transient=%s detail=%s",
                code,
                transient,
                self._redact(detail),
            )
        return CommandResult(
            ok=False, error_code=code, transient=transient, latency_ms=self._ms(started)
        )

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
    ) -> CommandResult:
        started = time.monotonic()
        if not self.configured:
            return self._failure(
                "LLM-NOT_CONFIGURED-001",
                False,
                started,
                "set AF_LLM_BASE_URL and AF_LLM_API_KEY in engine-api environment",
            )

        resolved_model = self._resolve_model(model)
        system_parts: list[str] = []
        if system:
            system_parts.append(system)
        if output_mode == "json":
            system_parts.append(_JSON_SYSTEM_HINT)
            if output_schema:
                system_parts.append(
                    "Follow this JSON Schema: "
                    + json.dumps(output_schema, ensure_ascii=False, separators=(",", ":"))
                )
        messages: list[dict[str, str]] = []
        if system_parts:
            messages.append({"role": "system", "content": "\n".join(system_parts)})
        messages.append({"role": "user", "content": prompt})

        payload: dict[str, Any] = {
            "model": resolved_model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": False,
        }
        if output_mode == "json" and self._json_response_format:
            payload["response_format"] = {"type": "json_object"}
        if self._send_seed:
            # derived_seed 是 hex sha256；截断到 32bit 作为上游 seed（仅确定性复现，不作幂等键）
            payload["seed"] = int(seed[:8], 16) % (2**31)

        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
            "X-Trace-Id": trace_id,
        }
        try:
            resp = await self._client.post(self._endpoint, json=payload, headers=headers)
        except httpx.TimeoutException as exc:
            return self._failure("LLM-TIMEOUT-001", True, started, type(exc).__name__)
        except httpx.HTTPError as exc:
            return self._failure("LLM-UPSTREAM-001", True, started, type(exc).__name__)

        if resp.status_code != 200:
            code, transient = self._classify_status(resp.status_code)
            return self._failure(code, transient, started, self._response_detail(resp))

        try:
            body = resp.json()
        except (ValueError, UnicodeDecodeError) as exc:
            return self._failure("LLM-RESPONSE-001", False, started, str(exc))
        if not isinstance(body, dict):
            return self._failure("LLM-RESPONSE-001", False, started, "body is not an object")

        text = self._extract_text(body)
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

        usage_raw = body.get("usage") if isinstance(body.get("usage"), dict) else {}
        fallback_prompt_tokens = estimate_tokens(prompt + (system or ""))
        prompt_tokens = _int_or(usage_raw.get("prompt_tokens"), fallback_prompt_tokens)
        completion_tokens = _int_or(usage_raw.get("completion_tokens"), estimate_tokens(text))
        total_tokens = _int_or(usage_raw.get("total_tokens"), prompt_tokens + completion_tokens)

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
            },
        )

    @staticmethod
    def _classify_status(status: int) -> tuple[str, bool]:
        if status in (401, 403):
            return "LLM-AUTH-001", False
        if status == 429:
            return "LLM-RATE_LIMIT-001", True
        if status in (400, 404, 422):
            return "LLM-REQUEST-001", False
        return "LLM-UPSTREAM-001", True

    @staticmethod
    def _response_detail(resp: httpx.Response) -> str:
        """仅保留状态码与截断的上游提示，绝不回传完整响应体。"""
        try:
            raw = resp.text[:_MAX_LOG_DETAIL]
        except UnicodeDecodeError:
            raw = ""
        return f"status={resp.status_code} body={raw}"

    @staticmethod
    def _extract_text(body: dict[str, Any]) -> str:
        choices = body.get("choices")
        if not isinstance(choices, list) or not choices:
            return ""
        first = choices[0]
        if not isinstance(first, dict):
            return ""
        message = first.get("message")
        if not isinstance(message, dict):
            return ""
        raw = message.get("content")
        if isinstance(raw, str):
            return raw.strip()
        if isinstance(raw, list):
            parts = [
                str(part.get("text", ""))
                for part in raw
                if isinstance(part, dict) and part.get("type") in (None, "text")
            ]
            return "".join(parts).strip()
        return ""

    async def aclose(self) -> None:
        await self._client.aclose()
