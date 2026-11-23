"""真实连接器（P2）：引擎 → gateway → connector-service 的内部调用。

信任边界（§5.2）：Python 侧只持连接器 id 与内部令牌，凭据全程留在 connector-service；
沙箱、域名白名单、私网拦截都在服务端完成，引擎无法绕过。
"""

from __future__ import annotations

import time
from typing import Any

import httpx

from ..base import CommandResult


class HttpToolConnector:
    """把 tool 节点的 Command 代理到 connector-service。"""

    def __init__(
        self,
        base_url: str,
        internal_token: str,
        connector_id: str,
        timeout_s: float = 35.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.connector_id = connector_id
        self._client = httpx.AsyncClient(base_url=base_url, timeout=timeout_s, transport=transport)
        self._headers = {"X-Internal-Token": internal_token}

    async def invoke(
        self,
        *,
        call_id: str,
        operation: str,
        params: dict[str, Any],
        timeout_ms: int,
        trace_id: str,
        space_id: str = "",
        mcp_method: str | None = None,
    ) -> CommandResult:
        started = time.monotonic()
        body = {
            "tenantId": space_id,
            "callId": call_id,
            "operation": operation,
            "params": params,
            "timeoutMs": timeout_ms,
            "traceId": trace_id,
        }
        if mcp_method is not None:
            body["mcpMethod"] = mcp_method
        try:
            resp = await self._client.post(
                f"/internal/connectors/{self.connector_id}/invoke",
                json=body,
                headers=self._headers,
                timeout=max(timeout_ms / 1000 + 5.0, 10.0),
            )
        except httpx.TimeoutException:
            return CommandResult(
                ok=False,
                error_code="CONN-TIMEOUT",
                transient=True,
                latency_ms=int((time.monotonic() - started) * 1000),
            )
        except httpx.HTTPError:
            return CommandResult(
                ok=False,
                error_code="CONN-UPSTREAM-001",
                transient=True,
                latency_ms=int((time.monotonic() - started) * 1000),
            )
        if resp.status_code == 404:
            return CommandResult(
                ok=False,
                error_code="CONN-NOT_FOUND-001",
                transient=False,
                latency_ms=int((time.monotonic() - started) * 1000),
            )
        if resp.status_code != 200:
            # 非 200 通常是网关/连接器服务自身异常；尽量沿用响应体里的错误码与瞬时性
            code, transient = "CONN-UPSTREAM-001", True
            try:
                payload = resp.json()
                code = str(payload.get("errorCode") or code)
                transient = bool(payload.get("transientError", True))
            except ValueError:
                pass
            return CommandResult(
                ok=False,
                error_code=code,
                transient=transient,
                latency_ms=int((time.monotonic() - started) * 1000),
            )
        try:
            payload = resp.json()
        except ValueError:
            # 200 但响应体不是合法 JSON：上游协议异常，按瞬时处理允许重试（C-10）
            return CommandResult(
                ok=False,
                error_code="CONN-BAD-JSON",
                transient=True,
                latency_ms=int((time.monotonic() - started) * 1000),
            )
        return CommandResult(
            ok=bool(payload.get("ok")),
            data=payload.get("data"),
            error_code=payload.get("errorCode"),
            transient=bool(payload.get("transientError")),
            latency_ms=int(payload.get("latencyMs") or 0),
        )

    async def aclose(self) -> None:
        await self._client.aclose()
