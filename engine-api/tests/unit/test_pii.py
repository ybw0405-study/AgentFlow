"""PII pii-v1：与 Java 侧规则逐条对齐（含 NFKC 与零宽字符注入）。"""

from __future__ import annotations

from app.governance.pii import contains_pii, detect_categories, mask_text


def test_phone_masked():
    # pii-v1 先 NFKC 归一化：全角标点变半角
    r = mask_text("我的电话是13812345678，请联系")
    assert r.masked == "我的电话是[PHONE],请联系"
    assert "PHONE" in r.categories and r.count == 1


def test_phone_with_country_prefix():
    assert contains_pii("打 +86 13812345678 找我")
    assert contains_pii("打+86-13812345678")


def test_id_card_18_masked():
    r = mask_text("身份证号11010119900307888X")
    assert "[ID_CARD]" in r.masked and "ID_CARD" in r.categories


def test_id_card_15_masked():
    r = mask_text("老证110101900307888")
    assert "[ID_CARD]" in r.masked


def test_email_masked_first_in_category_order():
    r = mask_text("邮箱 a@b.com，手机 13812345678")
    assert r.categories == ("EMAIL", "PHONE")
    assert r.masked == "邮箱 [EMAIL],手机 [PHONE]"  # NFKC：全角逗号→半角
    assert r.count == 2


def test_fullwidth_digits_normalized():
    r = mask_text("电话１３８１２３４５６７８")  # 全角数字
    assert "[PHONE]" in r.masked


def test_zero_width_injection_still_detected():
    r = mask_text("138​1234‍5678")  # 号码中插入零宽字符
    assert "[PHONE]" in r.masked


def test_plain_text_untouched():
    r = mask_text("没有问题的问题")
    assert r.count == 0 and r.categories == () and r.masked == "没有问题的问题"


def test_detect_categories():
    assert detect_categories("call 13900001111") == ("PHONE",)
