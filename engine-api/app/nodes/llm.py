"""llm 节点：模板渲染 → PII 第二层 → 预算预占 → LLMCommand。

纪律：
- PII 第二层（US-05）：渲染后的 prompt 再过一遍 pii-v2 mask；命中只记类别与数量，
  若二次脱敏后仍检出 PII（不应发生）则 PII-BLOCKED-001 永久失败。
- 预算硬停止（D11）：reserve 失败即 BudgetExhaustedError，Command 绝不发出；
  发出的 Command maxTokens 被钳到预占额度，保证 tokenUsed ≤ budget。
- 崩溃恢复：callId 确定性派生；mock provider 对同 (seed,prompt) 输出确定，
  重发安全。
"""

from __future__ import annotations

import logging
from typing import Any, Protocol

from ..commands.base import CommandKind, CommandResult, digest_of, make_call_id
from ..dsl.models import LlmNode
from ..errors import (
    BudgetExhaustedError,
    PermanentNodeError,
    PiiBlockedError,
    TransientNodeError,
)
from ..events.types import EventType
from ..expression.template import render_template
from ..governance.pii import contains_pii, mask_text
from ..governance.tokenmeter import estimate_tokens
from .base import NodeContext, NodeResult

logger = logging.getLogger(__name__)


class LLMProvider(Protocol):
    name: str

    async def complete(
        self,
        *,
        model: str,
        prompt: str,
        system: str | None,
        temperature: float,
        max_tokens: int,
        output_mode: str,
        output_schema: dict[str, Any] | None,
        seed: str,
        trace_id: str,
    ) -> CommandResult: ...


class LlmExecutor:
    node_type = "llm"

    def __init__(self, providers: dict[str, LLMProvider]) -> None:
        self._providers = providers

    async def aclose(self) -> None:
        """释放真实 provider 持有的 httpx 连接池（mock provider 无此资源）。"""
        for provider in self._providers.values():
            closer = getattr(provider, "aclose", None)
            if closer is not None:
                await closer()

    async def execute(self, node: LlmNode, ctx: NodeContext) -> NodeResult:
        cfg = node.config
        tctx = ctx.template_context()
        prompt = render_template(cfg.prompt_template, tctx)
        system = render_template(cfg.system_prompt, tctx) if cfg.system_prompt else None

        # PII 第二层
        l2 = mask_text(prompt)
        prompt = l2.masked
        if system is not None:
            system = mask_text(system).masked
        if l2.count:
            logger.info(
                "pii layer-2 masked categories=%s count=%d execution=%s",
                ",".join(l2.categories),
                l2.count,
                ctx.execution_id,
            )
        if contains_pii(prompt) or (system is not None and contains_pii(system)):
            raise PiiBlockedError()

        provider = self._providers.get(cfg.provider)
        if provider is None:
            raise PermanentNodeError("LLM-PROVIDER-001", f"unknown provider '{cfg.provider}'")

        # 预算预占（D11）：预留 = promptTokens + maxTokens，生成上限钳到剩余额度
        grant: int | None = None
        max_tokens = cfg.max_tokens
        prompt_tokens = estimate_tokens(prompt + (system or ""))
        if ctx.policy.token_budget is not None and ctx.budget is not None:
            grant = await ctx.budget.reserve(
                ctx.execution_id, ctx.policy.token_budget, prompt_tokens + cfg.max_tokens
            )
            if grant < 0:
                raise BudgetExhaustedError(
                    f"token budget {ctx.policy.token_budget} exhausted before node '{ctx.node_key}'"
                )
            max_tokens = grant - prompt_tokens
            if max_tokens < 1:
                await ctx.budget.settle(ctx.execution_id, grant, 0)
                raise BudgetExhaustedError(
                    f"token budget {ctx.policy.token_budget} cannot cover prompt "
                    f"for node '{ctx.node_key}'"
                )

        call_id = make_call_id(ctx.execution_id, ctx.node_key, ctx.attempt)
        request_digest = digest_of({"prompt": prompt, "system": system, "model": cfg.model})
        await ctx.emit(
            EventType.COMMAND_ISSUED,
            {
                "callId": call_id,
                "kind": str(CommandKind.LLM),
                "model": cfg.model,
                "requestDigest": request_digest,
                "attempt": ctx.attempt,
                **({"piiL2Categories": list(l2.categories)} if l2.count else {}),
            },
        )

        result: CommandResult | None = None
        try:
            result = await provider.complete(
                model=cfg.model,
                prompt=prompt,
                system=system,
                temperature=cfg.temperature,
                max_tokens=max_tokens,
                output_mode=cfg.output_mode,
                output_schema=cfg.output_schema,
                seed=ctx.derived_seed(),
                trace_id=ctx.trace_id,
            )
        except TransientNodeError as exc:
            await self._command_failed(ctx, call_id, exc.code)
            raise
        except Exception as exc:  # provider 未分类异常按瞬时处理（网络/5xx 类）
            await self._command_failed(ctx, call_id, "LLM-UPSTREAM-001")
            raise TransientNodeError("LLM-UPSTREAM-001", str(exc)) from exc

        if not result.ok:
            await self._command_failed(ctx, call_id, result.error_code or "LLM-UNKNOWN")
            if grant is not None and ctx.budget is not None:
                await ctx.budget.settle(ctx.execution_id, grant, 0)
            error_cls = TransientNodeError if result.transient else PermanentNodeError
            raise error_cls(result.error_code or "LLM-UNKNOWN", "llm command failed")

        await ctx.emit(
            EventType.COMMAND_COMPLETED,
            {
                "callId": call_id,
                "responseDigest": digest_of(result.data),
                "tokenUsed": result.token_usage,
                "latencyMs": result.latency_ms,
            },
        )
        if grant is not None and ctx.budget is not None:
            await ctx.budget.settle(ctx.execution_id, grant, result.token_usage)

        if result.data is None:
            raise PermanentNodeError("LLM-UPSTREAM-001", "provider returned empty data")
        usage = result.extra.get("usage") or {
            "prompt": None,
            "completion": None,
            "total": result.token_usage,
        }
        output = {
            "text": result.data.get("text", ""),
            "parsed": result.data.get("parsed"),
            "tokenUsage": usage,
            "model": cfg.model,
            "latencyMs": result.latency_ms,
        }
        return NodeResult(
            output=output,
            input_snapshot={
                "model": cfg.model,
                "prompt": prompt,
                "system": system,
                "temperature": cfg.temperature,
                "maxTokens": max_tokens,
            },
            token_used=result.token_usage,
        )

    @staticmethod
    async def _command_failed(ctx: NodeContext, call_id: str, code: str) -> None:
        await ctx.emit(EventType.COMMAND_FAILED, {"callId": call_id, "errorCode": code})
