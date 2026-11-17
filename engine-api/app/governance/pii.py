"""PII 第二层校验（Python 侧），与 Java 侧 pii-v2 规则逐条对齐（工程门禁）。

规则顺序：NFKC 归一化 → 去零宽字符 → EMAIL → ID_CARD_18 → ID_CARD_15 → PHONE
→ BANK_CARD（Luhn 校验）→ PASSPORT。
替换占位：[EMAIL] / [ID_CARD] / [PHONE] / [BANK_CARD] / [PASSPORT]（只暴露类别）。
categories 按首次命中顺序累计；count = 总替换次数。
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

VERSION = "pii-v2"

_ZERO_WIDTH = re.compile("[" + chr(0x200B) + "-" + chr(0x200D) + chr(0x2060) + chr(0xFEFF) + "]")
_EMAIL = re.compile(r"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", re.IGNORECASE)
_ID_CARD_18 = re.compile(
    r"(?<!\d)[1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)"
)
_ID_CARD_15 = re.compile(
    r"(?<!\d)[1-9]\d{5}\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}(?!\d)"
)
_PHONE = re.compile(r"(?<!\d)(?:\+?86[\s-]?)?1[3-9]\d{9}(?!\d)")
_BANK_CARD = re.compile(r"(?<!\d)\d{16,19}(?!\d)")
_PASSPORT = re.compile(r"(?<![A-Z0-9])[EGSDP][0-9]{8}(?![A-Z0-9])")

# (类别, 正则, 是否需 Luhn 校验)
_RULES: tuple[tuple[str, re.Pattern[str], bool], ...] = (
    ("EMAIL", _EMAIL, False),
    ("ID_CARD", _ID_CARD_18, False),
    ("ID_CARD", _ID_CARD_15, False),
    ("PHONE", _PHONE, False),
    ("BANK_CARD", _BANK_CARD, True),
    ("PASSPORT", _PASSPORT, False),
)


def _luhn_valid(digits: str) -> bool:
    """Luhn（模 10）：与 Java PiiMasker.luhnValid 逐条对齐。"""
    total = 0
    double_digit = False
    for ch in reversed(digits):
        if not ch.isdigit():
            return False
        d = int(ch)
        if double_digit:
            d *= 2
            if d > 9:
                d -= 9
        total += d
        double_digit = not double_digit
    return total % 10 == 0


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
    for category, pattern, luhn_check in _RULES:
        if luhn_check:
            hits = 0

            def repl(m: re.Match[str]) -> str:
                nonlocal hits
                if _luhn_valid(m.group()):
                    hits += 1
                    return f"[{category}]"
                return m.group()

            masked = pattern.sub(repl, masked)
            n = hits
        else:
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
