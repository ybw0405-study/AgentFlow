package com.agentflow.common.pii;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII 第一层脱敏（pii-v1）。与 Python 侧 governance/pii.py 规则逐条对齐——
 * 任何一侧修改规则必须同步另一侧并更新 parity 测试。
 *
 * <p>顺序：NFKC 归一化 → 去零宽字符 → EMAIL → ID_CARD_18 → ID_CARD_15 → PHONE。
 * 占位符只暴露类别：[EMAIL] / [ID_CARD] / [PHONE]。
 */
public final class PiiMasker {

    public static final String VERSION = "pii-v1";

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

    private PiiMasker() {
    }

    public record PiiMaskingResult(String masked, List<String> categories, int count) {
    }

    private static final List<Object[]> RULES = List.of(
            new Object[] {"EMAIL", EMAIL},
            new Object[] {"ID_CARD", ID_CARD_18},
            new Object[] {"ID_CARD", ID_CARD_15},
            new Object[] {"PHONE", PHONE});

    public static PiiMaskingResult mask(String text) {
        if (text == null || text.isEmpty()) {
            return new PiiMaskingResult(text, List.of(), 0);
        }
        String masked = ZERO_WIDTH.matcher(
                Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
        Set<String> categories = new LinkedHashSet<>();
        int count = 0;
        for (Object[] rule : RULES) {
            String category = (String) rule[0];
            Pattern pattern = (Pattern) rule[1];
            Matcher m = pattern.matcher(masked);
            StringBuffer sb = new StringBuffer();
            int hits = 0;
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement("[" + category + "]"));
                hits++;
            }
            m.appendTail(sb);
            masked = sb.toString();
            if (hits > 0) {
                count += hits;
                categories.add(category);
            }
        }
        return new PiiMaskingResult(masked, List.copyOf(categories), count);
    }

    public static boolean containsPii(String text) {
        return mask(text).count() > 0;
    }

    public static List<String> detectCategories(String text) {
        return mask(text).categories();
    }
}
