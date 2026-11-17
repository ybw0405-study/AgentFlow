"""token 计量。真实 provider 用其返回的 usage；mock/估算用 4 字符≈1 token。"""

from __future__ import annotations


def estimate_tokens(text: str) -> int:
    return max(1, len(text) // 4) if text else 0
