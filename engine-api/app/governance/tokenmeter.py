"""token 计量。真实 provider 用其返回的 usage；缺失时按字符类型加权估算。"""

from __future__ import annotations


def _is_cjk(ch: str) -> bool:
    code = ord(ch)
    return (
        0x3400 <= code <= 0x4DBF # CJK 扩展 A
        or 0x4E00 <= code <= 0x9FFF # 基本汉字
        or 0xF900 <= code <= 0xFAFF # 兼容汉字
        or 0x3000 <= code <= 0x303F # CJK 标点
        or 0xFF00 <= code <= 0xFFEF # 全角字符
    )


def estimate_tokens(text: str) -> int:
    """中文按 1 字≈1 token，其余按 4 字符≈1 token。

    纯 len//4 对中文提示词低估 3~4 倍，预占随之偏小；上游返回的真实 prompt_tokens
    不受 max_tokens 钳制，结算时超支若再不追扣，预算硬停止就成了摆设。
    """
    if not text:
        return 0
    cjk = sum(1 for ch in text if _is_cjk(ch))
    return max(1, cjk + (len(text) - cjk) // 4)
