package com.agentflow.workflow.gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 审批人表达式（{@code approvers.type = EXPRESSION}）：一条刻意做小的受控语言。
 *
 * <p>支持的语法子集：
 * <pre>
 * user.id == "u-1" and user.rank >= 2
 * user.id in ["u-1", "u-2"] or user.platformAdmin
 * not (space.id == "t-space-b")
 * </pre>
 *
 * <p>可用属性：{@code user.id / user.role / user.rank / user.platformAdmin}、
 * {@code space.id}。字面量支持字符串、整数、布尔、{@code null} 与列表。
 *
 * <p>为什么不用 SpEL：这段表达式直接决定「谁能批」，属于权限判定面；SpEL 即便用
 * SimpleEvaluationContext，仍有类型/方法解析面与版本差异。更关键的是——发布期的
 * 静态校验在 Python 引擎侧（{@code ast} 白名单），两边必须是同一套文法才能「发布通过
 * 就一定能求值」。自有子集是唯一能对齐的做法，代价是要多写一个几百行的解析器。
 *
 * <p>解析与求值分离：{@link #validate} 供发布期调用（不依赖用户），
 * {@link #evaluate} 供挂起瞬间按候选用户逐个判定。
 */
public final class GateExpression {

    /** 表达式里可以引用的用户属性。 */
    public static final Set<String> USER_ATTRS = Set.of("id", "role", "rank", "platformAdmin");
    /** 表达式里可以引用的空间属性。 */
    public static final Set<String> SPACE_ATTRS = Set.of("id");

    private GateExpression() {
    }

    /** 发布期静态校验：文法或属性名不合法时抛 IllegalArgumentException。 */
    public static void validate(String expression) {
        new Parser(tokenize(expression)).parseAll();
    }

    /** 按候选用户求值；返回 true 表示该用户满足这条审批人规则。 */
    public static boolean evaluate(String expression, String userId, String role, int rank,
                                   boolean platformAdmin, String spaceId) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", userId);
        user.put("role", role);
        user.put("rank", (long) rank);
        user.put("platformAdmin", platformAdmin);
        Map<String, Object> space = new LinkedHashMap<>();
        space.put("id", spaceId);
        Object value = new Parser(tokenize(expression)).parseAll().eval(user, space);
        return truthy(value);
    }

    // ---------- 词法 ----------

    private enum Kind { IDENT, STRING, NUMBER, OP, DOT, LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, EOF }

    private record Token(Kind kind, String text, int pos) {
    }

    private static List<Token> tokenize(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("审批人表达式为空");
        }
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char ch = source.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            switch (ch) {
                case '(' -> {
                    tokens.add(new Token(Kind.LPAREN, "(", i));
                    i++;
                }
                case ')' -> {
                    tokens.add(new Token(Kind.RPAREN, ")", i));
                    i++;
                }
                case '[' -> {
                    tokens.add(new Token(Kind.LBRACKET, "[", i));
                    i++;
                }
                case ']' -> {
                    tokens.add(new Token(Kind.RBRACKET, "]", i));
                    i++;
                }
                case ',' -> {
                    tokens.add(new Token(Kind.COMMA, ",", i));
                    i++;
                }
                case '.' -> {
                    tokens.add(new Token(Kind.DOT, ".", i));
                    i++;
                }
                case '"', '\'' -> {
                    char quote = ch;
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < source.length() && source.charAt(i) != quote) {
                        char c = source.charAt(i);
                        if (c == '\\' && i + 1 < source.length()) {
                            i++;
                            sb.append(source.charAt(i));
                        } else {
                            sb.append(c);
                        }
                        i++;
                    }
                    if (i >= source.length()) {
                        throw new IllegalArgumentException("字符串未闭合（位置 " + i + "）");
                    }
                    i++;
                    tokens.add(new Token(Kind.STRING, sb.toString(), i));
                }
                case '=', '!', '<', '>' -> {
                    int start = i;
                    i++;
                    if (i < source.length() && source.charAt(i) == '=') {
                        i++;
                    }
                    String op = source.substring(start, i);
                    if (!Set.of("==", "!=", "<", "<=", ">", ">=").contains(op)) {
                        throw new IllegalArgumentException("不支持的运算符 " + op);
                    }
                    tokens.add(new Token(Kind.OP, op, start));
                }
                default -> {
                    if (Character.isDigit(ch)) {
                        int start = i;
                        while (i < source.length() && Character.isDigit(source.charAt(i))) {
                            i++;
                        }
                        tokens.add(new Token(Kind.NUMBER, source.substring(start, i), start));
                    } else if (Character.isLetter(ch) || ch == '_') {
                        int start = i;
                        while (i < source.length()
                                && (Character.isLetterOrDigit(source.charAt(i))
                                || source.charAt(i) == '_')) {
                            i++;
                        }
                        tokens.add(new Token(Kind.IDENT, source.substring(start, i), start));
                    } else {
                        throw new IllegalArgumentException(
                                "非法字符 '" + ch + "'（位置 " + i + "）");
                    }
                }
            }
        }
        tokens.add(new Token(Kind.EOF, "", source.length()));
        return tokens;
    }

    // ---------- 语法 + 求值 ----------

    private interface Node {
        Object eval(Map<String, Object> user, Map<String, Object> space);
    }

    private record Literal(Object value) implements Node {
        @Override
        public Object eval(Map<String, Object> user, Map<String, Object> space) {
            return value;
        }
    }

    private record Attr(String scope, String name) implements Node {
        @Override
        public Object eval(Map<String, Object> user, Map<String, Object> space) {
            return "user".equals(scope) ? user.get(name) : space.get(name);
        }
    }

    private record ListLiteral(List<Node> items) implements Node {
        @Override
        public Object eval(Map<String, Object> user, Map<String, Object> space) {
            List<Object> values = new ArrayList<>(items.size());
            for (Node item : items) {
                values.add(item.eval(user, space));
            }
            return values;
        }
    }

    private record Not(Node inner) implements Node {
        @Override
        public Object eval(Map<String, Object> user, Map<String, Object> space) {
            return !truthy(inner.eval(user, space));
        }
    }

    private record BoolOp(boolean and, List<Node> parts) implements Node {
        @Override
        public Object eval(Map<String, Object> user, Map<String, Object> space) {
            for (Node part : parts) {
                boolean value = truthy(part.eval(user, space));
                if (and && !value) {
                    return false;
                }
                if (!and && value) {
                    return true;
                }
            }
            return and;
        }
    }

    private record Compare(Node left, String op, Node right) implements Node {
        @Override
        public Object eval(Map<String, Object> user, Map<String, Object> space) {
            Object l = left.eval(user, space);
            Object r = right.eval(user, space);
            return switch (op) {
                case "==" -> equalValues(l, r);
                case "!=" -> !equalValues(l, r);
                case "in" -> contains(r, l);
                case "not in" -> !contains(r, l);
                default -> compareOrdered(l, r, op);
            };
        }
    }

    private static boolean equalValues(Object l, Object r) {
        if (l == null || r == null) {
            return l == r;
        }
        if (l instanceof Number a && r instanceof Number b) {
            return a.longValue() == b.longValue();
        }
        return l.equals(r);
    }

    private static boolean contains(Object container, Object item) {
        if (container instanceof List<?> list) {
            for (Object candidate : list) {
                if (equalValues(candidate, item)) {
                    return true;
                }
            }
            return false;
        }
        if (container instanceof String text && item instanceof String needle) {
            return text.contains(needle);
        }
        return false;
    }

    private static boolean compareOrdered(Object l, Object r, String op) {
        int cmp;
        if (l instanceof Number a && r instanceof Number b) {
            cmp = Long.compare(a.longValue(), b.longValue());
        } else if (l instanceof String a && r instanceof String b) {
            cmp = a.compareTo(b);
        } else {
            return false;
        }
        return switch (op) {
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            default -> false;
        };
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Number number) {
            return number.longValue() != 0;
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private static final class Parser {

        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Node parseAll() {
            Node node = parseOr();
            if (peek().kind() != Kind.EOF) {
                throw new IllegalArgumentException(
                        "表达式在位置 " + peek().pos() + " 处有未消费的内容: " + peek().text());
            }
            return node;
        }

        private Node parseOr() {
            List<Node> parts = new ArrayList<>();
            parts.add(parseAnd());
            while (isKeyword("or")) {
                next();
                parts.add(parseAnd());
            }
            return parts.size() == 1 ? parts.get(0) : new BoolOp(false, parts);
        }

        private Node parseAnd() {
            List<Node> parts = new ArrayList<>();
            parts.add(parseNot());
            while (isKeyword("and")) {
                next();
                parts.add(parseNot());
            }
            return parts.size() == 1 ? parts.get(0) : new BoolOp(true, parts);
        }

        private Node parseNot() {
            if (isKeyword("not")) {
                next();
                return new Not(parseNot());
            }
            return parseComparison();
        }

        private Node parseComparison() {
            Node left = parsePrimary();
            Token token = peek();
            if (token.kind() == Kind.OP) {
                next();
                return new Compare(left, token.text(), parsePrimary());
            }
            if (isKeyword("in")) {
                next();
                return new Compare(left, "in", parsePrimary());
            }
            if (isKeyword("not")) {
                next();
                expectKeyword("in");
                return new Compare(left, "not in", parsePrimary());
            }
            return left;
        }

        private Node parsePrimary() {
            Token token = peek();
            switch (token.kind()) {
                case STRING, NUMBER -> {
                    next();
                    return new Literal(token.kind() == Kind.NUMBER
                            ? (Object) Long.parseLong(token.text()) : token.text());
                }
                case IDENT -> {
                    next();
                    // 关键字大小写不敏感（true / TRUE 都认）；user / space 与属性名区分大小写，
                    // 与发布期 ast 白名单、画布即时校验保持同一口径。
                    String raw = token.text();
                    switch (raw.toLowerCase(Locale.ROOT)) {
                        case "true" -> {
                            return new Literal(Boolean.TRUE);
                        }
                        case "false" -> {
                            return new Literal(Boolean.FALSE);
                        }
                        case "null", "none" -> {
                            return new Literal(null);
                        }
                        default -> {
                            if (!"user".equals(raw) && !"space".equals(raw)) {
                                throw new IllegalArgumentException(
                                        "未知标识符 " + raw
                                                + "（可用 user / space / and / or / not / in）");
                            }
                            expect(Kind.DOT, ".");
                            Token attr = next();
                            if (attr.kind() != Kind.IDENT) {
                                throw new IllegalArgumentException(
                                        "位置 " + attr.pos() + " 处缺少属性名");
                            }
                            Set<String> allowed = "user".equals(raw)
                                    ? USER_ATTRS : SPACE_ATTRS;
                            if (!allowed.contains(attr.text())) {
                                throw new IllegalArgumentException(
                                        "未知属性 " + raw + "." + attr.text()
                                                + "（可用 " + allowed + "）");
                            }
                            return new Attr(raw, attr.text());
                        }
                    }
                }
                case LPAREN -> {
                    next();
                    Node inner = parseOr();
                    expect(Kind.RPAREN, ")");
                    return inner;
                }
                case LBRACKET -> {
                    next();
                    List<Node> items = new ArrayList<>();
                    if (peek().kind() != Kind.RBRACKET) {
                        items.add(parseOr());
                        while (peek().kind() == Kind.COMMA) {
                            next();
                            items.add(parseOr());
                        }
                    }
                    expect(Kind.RBRACKET, "]");
                    return new ListLiteral(items);
                }
                default -> throw new IllegalArgumentException(
                        "位置 " + token.pos() + " 处无法解析: " + token.text());
            }
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private boolean isKeyword(String keyword) {
            Token token = peek();
            return token.kind() == Kind.IDENT
                    && token.text().toLowerCase(Locale.ROOT).equals(keyword);
        }

        private void expectKeyword(String keyword) {
            if (!isKeyword(keyword)) {
                throw new IllegalArgumentException("位置 " + peek().pos() + " 处缺少 " + keyword);
            }
            next();
        }

        private void expect(Kind kind, String text) {
            Token token = peek();
            if (token.kind() != kind) {
                throw new IllegalArgumentException(
                        "位置 " + token.pos() + " 处缺少 '" + text + "'");
            }
            next();
        }
    }
}
