package com.agentflow.common.pii;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII 第一层脱敏（pii-v2）。与 Python 侧 governance/pii.py 规则逐条对齐——
 * 任何一侧修改规则必须同步另一侧并更新 parity 测试。
 *
 * <p>顺序：NFKC 归一化 → 去零宽字符 → EMAIL → ID_CARD_18 → ID_CARD_15 → PHONE
 * → BANK_CARD（Luhn 校验）→ PASSPORT。
 * 占位符只暴露类别：[EMAIL] / [ID_CARD] / [PHONE] / [BANK_CARD] / [PASSPORT]。
 */
public final class PiiMasker {

    public static final String VERSION = "pii-v2";

    private static final Pattern ZERO_WIDTH = Pattern.compile("[\u200B-\u200D\u2060\uFEFF]");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_CARD_18 = Pattern.compile(
            "(?<!\\d)[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])"
                    + "\\d{3}[\\dXx](?!\\d)");
    private static final Pattern ID_CARD_15 = Pattern.compile(
            "(?<!\\d)[1-9]\\d{5}\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}(?!\\d)");
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)(?:\\+?86[\\s-]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern PASSPORT =
            Pattern.compile("(?<![A-Z0-9])[EGSDP][0-9]{8}(?![A-Z0-9])");

    private PiiMasker() {
    }

    public record PiiMaskingResult(String masked, List<String> categories, int count) {
    }

    /** 单条脱敏规则。luhn=true 时还要过模 10 校验才算命中（银行卡）。 */
    private record Rule(String category, Pattern pattern, boolean luhnCheck) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("EMAIL", EMAIL, false),
            new Rule("ID_CARD", ID_CARD_18, false),
            new Rule("ID_CARD", ID_CARD_15, false),
            new Rule("PHONE", PHONE, false),
            new Rule("BANK_CARD", BANK_CARD, true),
            new Rule("PASSPORT", PASSPORT, false));

    public static PiiMaskingResult mask(String text) {
        if (text == null || text.isEmpty()) {
            return new PiiMaskingResult(text, List.of(), 0);
        }
        String masked = ZERO_WIDTH.matcher(
                Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
        Set<String> categories = new LinkedHashSet<>();
        int count = 0;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(masked);
            StringBuffer sb = new StringBuffer();
            int hits = 0;
            while (m.find()) {
                String candidate = m.group();
                if (rule.luhnCheck() && !luhnValid(candidate)) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(candidate));
                    continue;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement("[" + rule.category() + "]"));
                hits++;
            }
            m.appendTail(sb);
            masked = sb.toString();
            if (hits > 0) {
                count += hits;
                categories.add(rule.category());
            }
        }
        return new PiiMaskingResult(masked, List.copyOf(categories), count);
    }

    /** Luhn（模 10）校验：过滤掉恰好 16-19 位但不是银行卡的随机数字。 */
    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int d = c - '0';
            if (doubleDigit) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    public static boolean containsPii(String text) {
        return mask(text).count() > 0;
    }

    public static List<String> detectCategories(String text) {
        return mask(text).categories();
    }
}
