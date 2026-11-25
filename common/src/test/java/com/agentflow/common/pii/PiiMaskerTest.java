package com.agentflow.common.pii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 与 Python 侧 tests/unit/test_pii.py 共享同一组测试向量（pii-v2 parity 门禁）。 */
class PiiMaskerTest {

    @Test
    void phoneMasked() {
        var r = PiiMasker.mask("我的电话是13812345678，请联系");
        assertEquals("我的电话是[PHONE],请联系", r.masked()); // NFKC：全角逗号→半角
        assertEquals(List.of("PHONE"), r.categories());
        assertEquals(1, r.count());
    }

    @Test
    void phoneWithCountryPrefix() {
        assertTrue(PiiMasker.containsPii("打 +86 13812345678 找我"));
        assertTrue(PiiMasker.containsPii("打+86-13812345678"));
    }

    @Test
    void idCard18Masked() {
        var r = PiiMasker.mask("身份证号11010119900307888X");
        assertTrue(r.masked().contains("[ID_CARD]"));
        assertEquals(List.of("ID_CARD"), r.categories());
    }

    @Test
    void idCard15Masked() {
        assertTrue(PiiMasker.mask("老证110101900307888").masked().contains("[ID_CARD]"));
    }

    @Test
    void emailFirstInCategoryOrder() {
        var r = PiiMasker.mask("邮箱 a@b.com，手机 13812345678");
        assertEquals(List.of("EMAIL", "PHONE"), r.categories());
        assertEquals("邮箱 [EMAIL],手机 [PHONE]", r.masked());
        assertEquals(2, r.count());
    }

    @Test
    void fullwidthDigitsNormalized() {
        assertTrue(PiiMasker.mask("电话１３８１２３４５６７８").masked().contains("[PHONE]"));
    }

    @Test
    void zeroWidthInjectionStillDetected() {
        // 号码中插入零宽字符（U+200B / U+200D），normalize 后仍应命中
        String sneaky = "138" + (char) 0x200B + "1234" + (char) 0x200D + "5678";
        assertTrue(PiiMasker.mask(sneaky).masked().contains("[PHONE]"));
    }

    @Test
    void plainTextUntouched() {
        var r = PiiMasker.mask("没有问题的问题");
        assertEquals(0, r.count());
        assertEquals("没有问题的问题", r.masked());
        assertFalse(PiiMasker.containsPii("没有问题的问题"));
    }

    @Test
    void bankCardMaskedWhenLuhnValid() {
        var r = PiiMasker.mask("卡号 4111111111111111 已绑定");
        assertTrue(r.masked().contains("[BANK_CARD]"));
        assertTrue(r.categories().contains("BANK_CARD"));
        assertFalse(r.masked().contains("4111111111111111"));
    }

    @Test
    void bankCardNotMaskedWhenLuhnInvalid() {
        var r = PiiMasker.mask("流水号 1234567890123456");
        assertFalse(r.masked().contains("[BANK_CARD]"));
    }

    @Test
    void passportMasked() {
        var r = PiiMasker.mask("护照 E12345678 已核验");
        assertTrue(r.masked().contains("[PASSPORT]"));
        assertTrue(r.categories().contains("PASSPORT"));
    }
}
