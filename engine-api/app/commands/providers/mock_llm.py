"""离线 mock LLM Provider（P0 默认，§11 P0.7：无外网可演示）。

确定性：同一 (seed, prompt, model) 永远生成同一文本与同一 token 计数，
保证重放/恢复语义可验证。
"""

from __future__ import annotations

import hashlib
import json
import time
from typing import Any

from ...governance.tokenmeter import estimate_tokens
from ..base import CommandResult


class MockLLMProvider:
    name = "mock"

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
        digest = hashlib.sha256(f"{seed}|{prompt}".encode()).hexdigest()
        head = prompt[:160].replace("\n", " ")
        text = f"[mock-llm:{model} #{digest[:8]}] 这是基于输入的确定性生成结果。输入摘要：{head}"
        text = text[: max_tokens * 4]  # 粗粒度按 4 字符/token 截断
        parsed: dict[str, Any] | None = None
        if output_mode == "json":
            parsed = {"text": text, "confidence": 0.9}
            text = json.dumps(parsed, ensure_ascii=False)
        prompt_tokens = estimate_tokens(prompt + (system or ""))
        completion_tokens = estimate_tokens(text)
        return CommandResult(
            ok=True,
            data={"text": text, "parsed": parsed},
            token_usage=prompt_tokens + completion_tokens,
            latency_ms=int((time.monotonic() - started) * 1000),
            extra={
                "usage": {
                    "prompt": prompt_tokens,
                    "completion": completion_tokens,
                    "total": prompt_tokens + completion_tokens,
                }
            },
        )
