"""llm 节点：模板渲染 → PII 第二层 → 预算预占 → LLMCommand。

纪律：
- PII 第二层（US-05）：渲染后的 prompt 再过一遍 pii-v2 mask；命中只记类别与数量，
  若二次脱敏后仍检出 PII（不应发生）则 PII-BLOCKED-001 永久失败。
- 预算硬停止（D11）：reserve 失败即 BudgetExhaustedError，Command 绝不发出；
  发出的 Command maxTokens 被钳到预占额度，保证 tokenUsed ≤ budget。
- 崩溃恢复：callId 确定性派生；外部模型调用是非确定性的，重放需防重复计费，
  重发安全。
"""

from __future__ import annotations

import logging
from typing import Any, Protocol

from ..commands.base import CommandKind, CommandResult, digest_of, make_call_id
from ..commands.providers.model_capabilities import (
    REASONING_MIN_OUTPUT_TOKENS,
    is_reasoning_model,
)
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
        call_id: str = "",
        space_id: str = "",
    ) -> CommandResult: ...


class LlmExecutor:
    node_type = "llm"

    def __init__(
        self,
        providers: dict[str, LLMProvider],
        connector_llms: Any | None = None,
    ) -> None:
        self._providers = providers
        # 连接器托管的模型：按 connectionId 懒加载 provider。为 None 表示当前部署未接连接器
        # （例如只跑离线单测），此时引用模型的节点会给出明确错误而不是静默回落。
        self._connector_llms = connector_llms

    def _provider_for(self, cfg: Any, authorized: frozenset[str]) -> LLMProvider:
        if cfg.connection_id:
            # 与 tool 节点同口径（SEC-4）：connectionId 必须在本执行授权集合内，
            # 否则跨空间引用连接器完全依赖 connector-service 内部校验，双路径强度不一致。
            if cfg.connection_id not in authorized:
                raise PermanentNodeError(
                    "CONN-FORBIDDEN-001",
                    f"connector '{cfg.connection_id}' is not authorized for this execution",
                )
            if self._connector_llms is None:
                raise PermanentNodeError(
                    "LLM-CONNECTION-001", "connector-backed models are not enabled"
                )
            return self._connector_llms.for_connection(cfg.connection_id)
        provider = self._providers.get(cfg.provider)
        if provider is None:
            raise PermanentNodeError("LLM-PROVIDER-001", f"unknown provider '{cfg.provider}'")
        return provider

    async def aclose(self) -> None:
        """释放真实 provider 持有的 httpx 连接池。"""
        for provider in self._providers.values():
            closer = getattr(provider, "aclose", None)
            if closer is not None:
                await closer()
        closer = getattr(self._connector_llms, "aclose", None)
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

        provider = self._provider_for(cfg, ctx.authorized_connector_ids)

        # 推理型模型的思维链与正文共用 max_tokens：DSL 默认的 1024 常被思考吃光，
        # 上游回一个空 content，节点以 LLM-RESPONSE-001 失败。下发前抬到下限，
        # 预算预占的请求量同步放大。不改写 DSL 配置 —— 实际下发值与抬升标记都
        # 记进事件和 input_snapshot，事后能解释为什么不是配置里的那个数。
        reasoning_adjusted = (
            is_reasoning_model(cfg.model) and cfg.max_tokens < REASONING_MIN_OUTPUT_TOKENS
        )
        max_tokens = (
            min(REASONING_MIN_OUTPUT_TOKENS, 32_000) if reasoning_adjusted else cfg.max_tokens
        )
        if reasoning_adjusted:
            logger.info(
                "reasoning model %s raises maxTokens %d -> %d execution=%s node=%s",
                cfg.model,
                cfg.max_tokens,
                max_tokens,
                ctx.execution_id,
                ctx.node_key,
            )

        # 预算预占（D11）：预留 = promptTokens + maxTokens，生成上限钳到剩余额度
        grant: int | None = None
        prompt_tokens = estimate_tokens(prompt + (system or ""))
        if ctx.policy.token_budget is not None and ctx.budget is not None:
            grant = await ctx.budget.reserve(
                ctx.execution_id, ctx.policy.token_budget, prompt_tokens + max_tokens
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
                **(
                    {"reasoningAdjusted": True, "configuredMaxTokens": cfg.max_tokens}
                    if reasoning_adjusted
                    else {}
                ),
            },
        )

        result: CommandResult | None = None
        # 流式只对纯文本模式有意义：JSON 模式必须一次性拿到完整结果做 schema 校验，
        # condition/下游变量引用同理依赖 COMMAND_COMPLETED 的完整结果。
        # 文本模式下是否流式由 provider 的配置（AF_LLM_STREAM）决定，这里显式传
        # stream=False 只在 JSON 模式强制关闭，其余交给 provider 内部判断。
        kwargs: dict[str, Any] = {
            "model": cfg.model,
            "prompt": prompt,
            "system": system,
            "temperature": cfg.temperature,
            "max_tokens": max_tokens,
            "output_mode": cfg.output_mode,
            "output_schema": cfg.output_schema,
            "seed": ctx.derived_seed(),
            "trace_id": ctx.trace_id,
            "call_id": call_id,
            "space_id": ctx.space_id,
        }
        if cfg.output_mode != "text" and _accepts_stream_kwarg(provider):
            kwargs["stream"] = False
        try:
            result = await provider.complete(**kwargs)
        except TransientNodeError as exc:
            await self._command_failed(ctx, call_id, exc.code)
            # provider 抛异常时未走到成功/失败结果分支，已 reserve 的 grant 必须在这里
            # 回冲，否则 pending 永远 > 0，reconcile 跳过，同执行后续节点预算被虚减。
            if grant is not None and ctx.budget is not None:
                await ctx.budget.settle(ctx.execution_id, grant, 0)
            raise
        except Exception as exc: # provider 未分类异常按瞬时处理（网络/5xx 类）
            await self._command_failed(ctx, call_id, "LLM-UPSTREAM-001")
            if grant is not None and ctx.budget is not None:
                await ctx.budget.settle(ctx.execution_id, grant, 0)
            raise TransientNodeError("LLM-UPSTREAM-001", str(exc)) from exc
        except BaseException:
            # asyncio.timeout 超时在 await provider.complete() 处抛 CancelledError，
            # 它继承 BaseException 而非 Exception，上面的分支都捕获不到。若不回冲，
            # 每次超时都会泄漏一笔 grant、:inflight +1，导致同执行后续节点被虚减的
            # 余额提前触发预算硬停止。注意此处只回冲、不吞异常。
            if grant is not None and ctx.budget is not None:
                await ctx.budget.settle(ctx.execution_id, grant, 0)
            raise

        if not result.ok:
            await self._command_failed(ctx, call_id, result.error_code or "LLM-UNKNOWN")
            if grant is not None and ctx.budget is not None:
                await ctx.budget.settle(ctx.execution_id, grant, 0)
            error_cls = TransientNodeError if result.transient else PermanentNodeError
            raise error_cls(result.error_code or "LLM-UNKNOWN", "llm command failed")

        # provider 可能把占位模型名回落到服务端默认模型；审计需记录真实下发的模型名
        resolved_model = str(result.extra.get("model") or cfg.model)

        # 流式增量作为展示层事件推送：严格落在 COMMAND_COMPLETED 之前，
        # 且绝不影响 NodeResult —— 下游只消费完整结果。无增量（非流式 / 上游不支持）
        # 时这一整块为空，行为与之前逐字节一致。
        deltas = result.extra.get("deltas")
        if isinstance(deltas, list):
            for idx, delta in enumerate(deltas):
                if not isinstance(delta, str) or not delta:
                    continue
                await ctx.emit(
                    EventType.OUTPUT_DELTA,
                    {
                        "callId": call_id,
                        "index": idx,
                        "text": delta,
                    },
                )

        await ctx.emit(
            EventType.COMMAND_COMPLETED,
            {
                "callId": call_id,
                "responseDigest": digest_of(result.data),
                "tokenUsed": result.token_usage,
                "latencyMs": result.latency_ms,
                "model": resolved_model,
                **({"requestedModel": cfg.model} if resolved_model != cfg.model else {}),
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
            "model": resolved_model,
            "latencyMs": result.latency_ms,
        }
        if resolved_model != cfg.model:
            output["requestedModel"] = cfg.model
        return NodeResult(
            output=output,
            input_snapshot={
                "model": cfg.model,
                "prompt": prompt,
                "system": system,
                "temperature": cfg.temperature,
                "maxTokens": max_tokens,
                **(
                    {"reasoningAdjusted": True, "configuredMaxTokens": cfg.max_tokens}
                    if reasoning_adjusted
                    else {}
                ),
            },
            token_used=result.token_usage,
        )

    @staticmethod
    async def _command_failed(ctx: NodeContext, call_id: str, code: str) -> None:
        await ctx.emit(EventType.COMMAND_FAILED, {"callId": call_id, "errorCode": code})


def _accepts_stream_kwarg(provider: LLMProvider) -> bool:
    """探测 provider 是否接受 stream 关键字。

    只有直连 openai-compatible 提供该参数；连接器路径（connector_llm）经
    connector-service 单跳代理，拿不到 SSE 增量，签名里没有 stream。
    探测用函数签名而非 name 字符串，避免 future provider 引入 stream 时漏判。
    """
    import inspect

    try:
        return "stream" in inspect.signature(provider.complete).parameters
    except (TypeError, ValueError):
        return False
