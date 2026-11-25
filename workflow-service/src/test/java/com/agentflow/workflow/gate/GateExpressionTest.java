package com.agentflow.workflow.gate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 审批人表达式：文法边界与求值语义。
 *
 * <p>重点不在「能算 1+1」，而在权限口径：rank 是数值、role 是字符串、列表 in 必须命中，
 * 任何越界写法都要在发布期抛错（否则运行期解析出空审批人 = 审批卡死）。
 */
class GateExpressionTest {

    @Test
    void rankComparisonMatchesRoleOrder() {
        String expr = "user.rank >= 2";
        assertTrue(GateExpression.evaluate(expr, "u-1", "EDITOR", 2, false, "s-1"));
        assertTrue(GateExpression.evaluate(expr, "u-1", "OWNER", 4, false, "s-1"));
        assertFalse(GateExpression.evaluate(expr, "u-1", "VIEWER", 1, false, "s-1"));
    }

    @Test
    void membershipOnListLiteral() {
        String expr = "user.id in [\"u-1\", \"u-2\"]";
        assertTrue(GateExpression.evaluate(expr, "u-2", "VIEWER", 1, false, "s-1"));
        assertFalse(GateExpression.evaluate(expr, "u-3", "VIEWER", 1, false, "s-1"));
    }

    @Test
    void notInAndNegation() {
        String expr = "not (user.id in [\"u-9\"]) and user.role != \"VIEWER\"";
        assertTrue(GateExpression.evaluate(expr, "u-1", "ADMIN", 3, false, "s-1"));
        assertFalse(GateExpression.evaluate(expr, "u-9", "ADMIN", 3, false, "s-1"));
        assertFalse(GateExpression.evaluate(expr, "u-1", "VIEWER", 1, false, "s-1"));
    }

    @Test
    void platformAdminFlagAndSpaceScope() {
        assertTrue(GateExpression.evaluate("user.platformAdmin", "u-1", "ADMIN", 3, true, "s-1"));
        assertTrue(GateExpression.evaluate("space.id == \"s-1\" and user.rank > 0",
                "u-1", "VIEWER", 1, false, "s-1"));
        assertFalse(GateExpression.evaluate("space.id == \"s-2\"",
                "u-1", "VIEWER", 1, false, "s-1"));
    }

    @Test
    void orShortCircuits() {
        assertTrue(GateExpression.evaluate("user.rank >= 4 or user.platformAdmin",
                "u-1", "VIEWER", 1, true, "s-1"));
    }

    @Test
    void rejectsUnknownAttribute() {
        assertThrows(IllegalArgumentException.class,
                () -> GateExpression.validate("user.level >= 2"));
        assertThrows(IllegalArgumentException.class,
                () -> GateExpression.validate("tenant.id == \"t\""));
    }

    @Test
    void rejectsCallsAndIndexing() {
        assertThrows(IllegalArgumentException.class,
                () -> GateExpression.validate("user.id.startswith(\"u\")"));
        assertThrows(IllegalArgumentException.class,
                () -> GateExpression.validate("len(user.id) > 3"));
        assertThrows(IllegalArgumentException.class,
                () -> GateExpression.validate("user.id[0] == \"u\""));
    }

    @Test
    void rejectsDanglingOperatorsAndUnclosedLiteral() {
        assertThrows(IllegalArgumentException.class,
                () -> GateExpression.validate("user.rank >="));
        assertThrows(IllegalArgumentException.class,
                () -> GateExpression.validate("user.id == \"u-1"));
        assertThrows(IllegalArgumentException.class, () -> GateExpression.validate(" "));
    }

    @Test
    void literalKeywordsAreCaseInsensitive() {
        // 画布与发布期都认 true / null 这类 JSON 写法，求值侧不能只认小写
        assertTrue(GateExpression.evaluate("user.platformAdmin == TRUE", "u-1", "VIEWER", 1, true, "s-1"));
        assertTrue(GateExpression.evaluate("user.platformAdmin == true", "u-1", "VIEWER", 1, true, "s-1"));
        assertTrue(GateExpression.evaluate("user.role != null", "u-1", "VIEWER", 1, false, "s-1"));
    }

    @Test
    void scopeIdentifiersAreCaseSensitive() {
        // 标识符区分大小写：User.id 应被当成未知标识符，而不是悄悄等价于 user.id
        assertThrows(IllegalArgumentException.class, () -> GateExpression.validate("User.id == \"u-1\""));
    }

    @Test
    void bareAttributeWithoutScopeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GateExpression.validate("rank >= 2"));
    }
}
