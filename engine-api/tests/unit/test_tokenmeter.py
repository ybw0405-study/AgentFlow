from app.governance.tokenmeter import estimate_tokens


def test_cjk_prompt_is_not_undercounted_by_four_chars_per_token() -> None:
    """中文 1 字≈1 token；旧的 len//4 会低估 3~4 倍并击穿预算预占。"""
    assert estimate_tokens("中文提示词" * 20) == 100
    assert estimate_tokens("abcd" * 25) == 25
    assert estimate_tokens("") == 0
    assert estimate_tokens("中文 mixed 文本") > 0
