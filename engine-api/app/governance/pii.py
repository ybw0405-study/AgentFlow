"""PII 第二层校验（Python 侧），与 Java 侧 pii-v1 规则逐条对齐（工程门禁）。

规则顺序：NFKC 归一化 → 去零宽字符 → EMAIL → ID_CARD_18 → ID_CARD_15 → PHONE。
替换占位：[EMAIL] / [ID_CARD] / [PHONE]（不暴露内容，只暴露类别）。
categories 按首次命中顺序累计；count = 总替换次数。
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

VERSION = "pii-v1"

_ZERO_WIDTH = re.compile("[" + chr(0x200B) + "-" + chr(0x200D) + chr(0x2060) + chr(0xFEFF) + "]")
_EMAIL = re.compile(r"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", re.IGNORECASE)
_ID_CARD_18 = re.compile(
    r"(?<!\d)[1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)"
)
_ID_CARD_15 = re.compile(
    r"(?<!\d)[1-9]\d{5}\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}(?!\d)"
)
_PHONE = re.compile(r"(?<!\d)(?:\+?86[\s-]?)?1[3-9]\d{9}(?!\d)")

_RULES: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("EMAIL", _EMAIL),
    ("ID_CARD", _ID_CARD_18),
    ("ID_CARD", _ID_CARD_15),
    ("PHONE", _PHONE),
)


@dataclass(frozen=True)
class MaskResult:
    masked: str
    categories: tuple[str, ...]
    count: int


def normalize(text: str) -> str:
    return _ZERO_WIDTH.sub("", unicodedata.normalize("NFKC", text))


def mask_text(text: str) -> MaskResult:
    masked = normalize(text)
    categories: list[str] = []
    count = 0
    for category, pattern in _RULES:
        masked, n = pattern.subn(f"[{category}]", masked)
        if n:
            count += n
            if category not in categories:
                categories.append(category)
    return MaskResult(masked=masked, categories=tuple(categories), count=count)


def contains_pii(text: str) -> bool:
    return mask_text(text).count > 0


def detect_categories(text: str) -> tuple[str, ...]:
    return mask_text(text).categories
