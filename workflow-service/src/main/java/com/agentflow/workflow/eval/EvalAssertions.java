package com.agentflow.workflow.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 评测断言（纯函数，无 IO）。
 *
 * <p>四种模式，够用且不引入表达式执行：
 * <ul>
 * <li>EXACT：期望与实际的 result 深度相等（适合结构化输出）</li>
 * <li>CONTAINS：期望里的每段文本都出现在实际 JSON 序列化结果中（适合自由文本）</li>
 * <li>EXISTS：按 path 取值存在且非空（适合「有没有答出来」）</li>
 * <li>NUMERIC：按 path 取数值，落在 [min, max]（适合打分/置信度）</li>
 * </ul>
 * 断言里出现未知 mode 一律判失败（宁错不静默）。
 */
public final class EvalAssertions {

    public record Result(boolean passed, String message) {
        static Result pass() {
            return new Result(true, "ok");
        }

        static Result fail(String message) {
            return new Result(false, message);
        }
    }

    private EvalAssertions() {
    }

    public static Result evaluate(Map<String, Object> assertion, Object expected, Object actual) {
        String mode = String.valueOf(assertion.getOrDefault("mode", "EXACT")).toUpperCase();
        return switch (mode) {
            case "EXACT" -> {
                boolean same = Objects.equals(normalize(expected), normalize(actual));
                yield same ? Result.pass()
                        : Result.fail("exact mismatch: expected " + brief(expected)
                                + " but got " + brief(actual));
            }
            case "CONTAINS" -> contains(expected, actual);
            case "EXISTS" -> {
                String path = str(assertion.get("path"));
                Object value = lookup(actual, path);
                yield value == null || String.valueOf(value).isBlank()
                        ? Result.fail("path '" + path + "' is empty")
                        : Result.pass();
            }
            case "NUMERIC" -> numeric(assertion, actual);
            default -> Result.fail("unknown assertion mode: " + mode);
        };
    }

    private static Result contains(Object expected, Object actual) {
        List<String> needles = new ArrayList<>();
        collectText(expected, needles);
        if (needles.isEmpty()) {
            return Result.fail("contains assertion needs non-empty expected text");
        }
        String haystack = String.valueOf(normalize(actual));
        for (String needle : needles) {
            if (!haystack.contains(needle)) {
                return Result.fail("missing expected text: " + needle);
            }
        }
        return Result.pass();
    }

    private static Result numeric(Map<String, Object> assertion, Object actual) {
        String path = str(assertion.get("path"));
        Object value = lookup(actual, path);
        if (!(value instanceof Number number)) {
            return Result.fail("path '" + path + "' is not numeric: " + value);
        }
        double v = number.doubleValue();
        Object min = assertion.get("min");
        Object max = assertion.get("max");
        if (min instanceof Number m && v < m.doubleValue()) {
            return Result.fail("value " + v + " < min " + m.doubleValue());
        }
        if (max instanceof Number m && v > m.doubleValue()) {
            return Result.fail("value " + v + " > max " + m.doubleValue());
        }
        return Result.pass();
    }

    private static void collectText(Object value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof String s) {
            if (!s.isBlank()) {
                out.add(s);
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectText(v, out));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collectText(v, out));
        } else {
            out.add(String.valueOf(value));
        }
    }

    /** 点号路径取值：a.b.0.c；任一层缺失返回 null。 */
    static Object lookup(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return root;
        }
        Object cursor = root;
        for (String segment : path.split("\\.")) {
            if (cursor instanceof Map<?, ?> map) {
                cursor = map.get(segment);
            } else if (cursor instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(segment);
                    cursor = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
            if (cursor == null) {
                return null;
            }
        }
        return cursor;
    }

    private static Object normalize(Object value) {
        if (value instanceof Number number) {
            // 12 与 12.0 在评测里应等价（DSL 输出常被 JSON 化成小数）
            double d = number.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), normalize(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(EvalAssertions::normalize).toList();
        }
        return value;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String brief(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
}
