"""OpenAI 兼容 Chat Completions Provider（真实模型）。

信任边界：
- 端点与密钥只来自环境变量（AF_LLM_*），绝不来自 DSL / 用户入参，避免 SSRF 与凭据注入。
- 密钥只在 Authorization 头出现；日志、错误码、事件载荷中一律脱敏。
- 不重试：重试由引擎 RetryPolicy 统一治理（§8.4），避免双层重试导致重复计费；
  provider 只负责把上游状态精确分类为 transient / permanent，让引擎决定是否重放。

崩溃恢复语义：真实模型调用是外部副作用且非确定性，重放时可能重复计费
（at-least-once）。可通过 AF_LLM_SEND_SEED=true 向上游传递确定性 seed（仅部分厂商支持）。
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass
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
_MODELS_TIMEOUT_S = 15.0
_MAX_MODEL_ENTRIES = 200
_MAX_MODEL_ID_LEN = 200


def int_or(value: Any, fallback: int) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return fallback
    return max(0, int(value))


def build_chat_payload(
    *,
    model: str,
    prompt: str,
    system: str | None,
    temperature: float,
    max_tokens: int,
    output_mode: str,
    output_schema: dict[str, Any] | None,
    json_response_format: bool,
    seed_value: int | None = None,
) -> dict[str, Any]:
    """构造 OpenAI 兼容 {@code /chat/completions} 请求体。

    直连端点与经连接器代理两条路径共用这一份构造逻辑：请求体必须完全一致，
    否则同一个工作流换一种接法就会得到不同结果。
    """
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
        "model": model,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": False,
    }
    if output_mode == "json" and json_response_format:
        payload["response_format"] = {"type": "json_object"}
    if seed_value is not None:
        payload["seed"] = seed_value
    return payload


def extract_text(body: Any) -> str:
    """从 chat completions 响应里取出助手文本；兼容 string 与分片 text 数组两种形态。"""
    if not isinstance(body, dict):
        return ""
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


def classify_status(status: int) -> tuple[str, bool]:
    """把上游状态码归为（错误码，是否瞬时）；瞬时错误交给引擎 RetryPolicy 决定重试。"""
    if status in (401, 403):
        return "LLM-AUTH-001", False
    if status == 429:
        return "LLM-RATE_LIMIT-001", True
    if status in (400, 404, 422):
        return "LLM-REQUEST-001", False
    return "LLM-UPSTREAM-001", True


@dataclass(frozen=True)
class ModelCatalog:
    """上游模型清单。

    configured=False 表示进程未配置 AF_LLM_*，此时 models 为空是正常状态而非故障；
    error 只携带错误码，绝不回传上游响应体（可能含内部信息）。
    """

    configured: bool
    models: list[str]
    error: str | None = None


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
        """历史草稿里的占位模型名不能在真实端点上下发，回落到默认模型。"""
        if not model or model.lower().startswith("mock"):
            return self._default_model
        return model

    def _redact(self, text: str) -> str:
        safe = text.replace(self._api_key, "***") if self._api_key else text
        return safe[:_MAX_LOG_DETAIL]

    async def list_models(self) -> ModelCatalog:
        """读取上游 /models，供画布下拉选择。

        端点由 chat completions 端点反推，只访问已配置的那一台主机；返回体只取
        `data[].id`，其余字段（owner、权限等）一律丢弃。失败只回错误码，让前端
        能降级成手填而不是整块配置不可用。
        """
        if not self.configured:
            return ModelCatalog(configured=False, models=[])

        url = self._endpoint[: -len(_CHAT_COMPLETIONS_PATH)] + "/models"
        try:
            resp = await self._client.get(
                url,
                headers={"Authorization": f"Bearer {self._api_key}"},
                timeout=min(self._timeout_s, _MODELS_TIMEOUT_S),
            )
        except httpx.HTTPError as exc:
            logger.warning("llm list_models transport failure detail=%s", type(exc).__name__)
            return ModelCatalog(configured=True, models=[], error="LLM-UPSTREAM-001")

        if resp.status_code != 200:
            code, _ = self._classify_status(resp.status_code)
            logger.warning("llm list_models rejected status=%s code=%s", resp.status_code, code)
            return ModelCatalog(configured=True, models=[], error=code)

        try:
            body = resp.json()
        except (ValueError, UnicodeDecodeError):
            return ModelCatalog(configured=True, models=[], error="LLM-RESPONSE-001")
        raw = body.get("data") if isinstance(body, dict) else None
        if not isinstance(raw, list):
            return ModelCatalog(configured=True, models=[], error="LLM-RESPONSE-001")

        models: list[str] = []
        seen: set[str] = set()
        for item in raw:
            model_id = item.get("id") if isinstance(item, dict) else None
            if not isinstance(model_id, str):
                continue
            model_id = model_id.strip()[:_MAX_MODEL_ID_LEN]
            if model_id and model_id not in seen:
                seen.add(model_id)
                models.append(model_id)
                if len(models) >= _MAX_MODEL_ENTRIES:
                    break
        # 默认模型可能是上游只在请求侧接受、不在 /models 里列出的别名（例如 DeepSeek 的
        # deepseek-chat）。画布下拉要能回显当前生效的模型，所以把它并入清单。
        default_model = (self._default_model or "").strip()[:_MAX_MODEL_ID_LEN]
        if default_model and default_model not in seen:
            models.append(default_model)
        return ModelCatalog(configured=True, models=sorted(models))

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
        call_id: str = "",
        space_id: str = "",
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
        seed_value = int(seed[:8], 16) % (2**31) if self._send_seed else None
        # derived_seed 是 hex sha256；截断到 32bit 作为上游 seed（仅确定性复现，不作幂等键）
        payload = build_chat_payload(
            model=resolved_model,
            prompt=prompt,
            system=system,
            temperature=temperature,
            max_tokens=max_tokens,
            output_mode=output_mode,
            output_schema=output_schema,
            json_response_format=self._json_response_format,
            seed_value=seed_value,
        )

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
            detail = "empty content"
            choices = body.get("choices")
            if isinstance(choices, list) and choices and isinstance(choices[0], dict):
                message = choices[0].get("message")
                if isinstance(message, dict) and message.get("reasoning_content"):
                    detail = (
                        "empty content: model spent the token budget on reasoning; raise maxTokens"
                    )
            return self._failure("LLM-RESPONSE-001", False, started, detail)
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
        prompt_tokens = int_or(usage_raw.get("prompt_tokens"), fallback_prompt_tokens)
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
            },
        )

    @staticmethod
    def _classify_status(status: int) -> tuple[str, bool]:
        return classify_status(status)

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
        return extract_text(body)

    async def aclose(self) -> None:
        await self._client.aclose()
