package com.agentflow.workflow.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentflow.workflow.execution.BudgetLedgerMapper.BudgetLedgerRow;
import com.agentflow.workflow.tenant.TenantMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 有效限额：沿 parent 链取最小，并记下「是谁卡住的」。 */
class TenantLimitResolverTest {

    private static TenantMapper.TenantRow tenant(String id, String name, String parent, String type) {
        return new TenantMapper.TenantRow(id, name, parent, type);
    }

    private static BudgetLedgerRow ledger(String id, int callsUsed, long tokensUsed,
                                          int limitCalls, long limitTokens) {
        return new BudgetLedgerRow(id, "2026-11", callsUsed, tokensUsed, limitCalls, limitTokens);
    }

    @Test
    void ownLimitWinsWhenItIsTheTightest() {
        var tenants = List.of(tenant("org", "Org", null, "ORG"), tenant("space", "Space", "org", "SPACE"));
        var rows = List.of(ledger("org", 0, 0, 50_000, 500_000), ledger("space", 10, 100, 5_000, 50_000));

        var resolved = TenantLimitResolver.resolve(tenants, TenantLimitResolver.index(rows));
        var space = resolved.get(1);

        assertEquals(5_000, space.effectiveCallLimit());
        assertEquals(50_000L, space.effectiveTokenLimit());
        // 限额来自自己：这不是「继承」，改自己的账本行就能生效
        assertFalse(space.inherited());
        assertEquals("space", space.limitedByToken());
        assertEquals("Space", space.limitedByTokenName());
    }

    @Test
    void ancestorLimitBeatsChildEvenWhenChildIsHigher() {
        var tenants = List.of(tenant("org", "Org", null, "ORG"), tenant("space", "Space", "org", "SPACE"));
        var rows = List.of(ledger("org", 0, 0, 1_000, 10_000), ledger("space", 10, 100, 9_000, 90_000));

        var space = TenantLimitResolver.resolve(tenants, TenantLimitResolver.index(rows)).get(1);

        // 子租户账本写着 9,000，但真实上限是父的 1,000 —— 提高自己的限额不会生效
        assertEquals(1_000, space.effectiveCallLimit());
        assertEquals(10_000L, space.effectiveTokenLimit());
        assertEquals(9_000, space.ownCallLimit());
        assertTrue(space.inherited());
        assertEquals("org", space.limitedByToken());
        assertEquals("Org", space.limitedByTokenName());
    }

    @Test
    void threeLevelChainTakesTheGrandparentWhenParentIsLooser() {
        var tenants = List.of(tenant("org", "Org", null, "ORG"),
                tenant("team", "Team", "org", "TEAM"),
                tenant("space", "Space", "team", "SPACE"));
        var rows = List.of(ledger("org", 0, 0, 1_000, 10_000),
                ledger("team", 0, 0, 8_000, 80_000),
                ledger("space", 5, 50, 9_000, 90_000));

        var space = TenantLimitResolver.resolve(tenants, TenantLimitResolver.index(rows)).get(2);

        assertEquals(1_000, space.effectiveCallLimit());
        assertEquals("org", space.limitedByCall());
        assertEquals("Org", space.limitedByCallName());
    }

    @Test
    void callsAndTokensCanBeLimitedByDifferentAncestors() {
        // 父很宽但调用次数很紧，祖父 token 很紧：两个维度指向不同的人，不能只报一个人的名字
        var tenants = List.of(tenant("org", "Org", null, "ORG"),
                tenant("team", "Team", "org", "TEAM"),
                tenant("space", "Space", "team", "SPACE"));
        var rows = List.of(ledger("org", 0, 0, 100_000, 10_000),
                ledger("team", 0, 0, 100, 1_000_000),
                ledger("space", 1, 10, 100_000, 1_000_000));

        var space = TenantLimitResolver.resolve(tenants, TenantLimitResolver.index(rows)).get(2);

        assertEquals(100, space.effectiveCallLimit());
        assertEquals("team", space.limitedByCall());
        assertEquals(10_000L, space.effectiveTokenLimit());
        assertEquals("org", space.limitedByToken());
    }

    @Test
    void tenantWithoutLedgerRowIsUnboundedAndCountsAsZeroUsage() {
        var tenants = List.of(tenant("org", "Org", null, "ORG"), tenant("space", "Space", "org", "SPACE"));

        var resolved = TenantLimitResolver.resolve(tenants, Map.of());
        var space = resolved.get(1);

        assertEquals(0, space.callsUsed());
        assertEquals(0L, space.tokensUsed());
        assertNull(space.effectiveCallLimit());
        assertNull(space.effectiveTokenLimit());
        assertEquals("unbounded", space.state());
        assertEquals(0.0, space.tokenUtilization());
    }

    @Test
    void childWithoutOwnRowInheritsAncestorLimits() {
        // 子租户还没跑过东西（没有账本行），但额度是父给的：上限仍然存在
        var tenants = List.of(tenant("org", "Org", null, "ORG"), tenant("space", "Space", "org", "SPACE"));
        var rows = List.of(ledger("org", 0, 0, 1_000, 10_000));

        var space = TenantLimitResolver.resolve(tenants, TenantLimitResolver.index(rows)).get(1);

        assertEquals(1_000, space.effectiveCallLimit());
        assertEquals("org", space.limitedByToken());
        assertEquals("ok", space.state());
        // 自身没有限额可言，所以「继承」为真（限额完全来自祖先）
        assertTrue(space.inherited());
    }

    @Test
    void overQuotaIsReportedEvenWhenUtilizationIsClamped() {
        var tenants = List.of(tenant("org", "Org", null, "ORG"));
        var rows = List.of(ledger("org", 2_448, 132_501, 10_000, 50_000));

        var org = TenantLimitResolver.resolve(tenants, TenantLimitResolver.index(rows)).get(0);

        // 数字照实给出，进度条由 ratio 夹到 1 —— 结论放在 state 里，不靠利用率反推
        assertEquals(132_501L, org.tokensUsed());
        assertEquals(1.0, org.tokenUtilization());
        assertEquals("over", org.state());
    }

    @Test
    void nearThresholdIsEightyPercent() {
        var tenants = List.of(tenant("org", "Org", null, "ORG"));

        var just = TenantLimitResolver.resolve(tenants,
                TenantLimitResolver.index(List.of(ledger("org", 80, 8_000, 100, 10_000)))).get(0);
        var below = TenantLimitResolver.resolve(tenants,
                TenantLimitResolver.index(List.of(ledger("org", 79, 7_999, 100, 10_000)))).get(0);

        assertEquals("near", just.state());
        assertEquals("ok", below.state());
    }

    @Test
    void zeroLimitsMeanNoLimitRatherThanInstantOverQuota() {
        // 账本行可能是老数据或手工插的 0；按「没设限额」处理，与 ratio 的口径一致
        var tenants = List.of(tenant("org", "Org", null, "ORG"));
        var rows = List.of(ledger("org", 5, 50, 0, 0));

        var org = TenantLimitResolver.resolve(tenants, TenantLimitResolver.index(rows)).get(0);

        assertNull(org.effectiveCallLimit());
        assertEquals("unbounded", org.state());
        assertEquals(0.0, org.tokenUtilization());
    }

    @Test
    void parentCycleDoesNotHang() {
        // 正常流程建不出环，但手工改库能造出来；解析必须自己停下来
        var tenants = List.of(tenant("a", "A", "b", "TEAM"), tenant("b", "B", "a", "TEAM"));
        var rows = List.of(ledger("a", 1, 10, 100, 1_000), ledger("b", 1, 10, 200, 2_000));

        var a = TenantLimitResolver.resolve(tenants, TenantLimitResolver.index(rows)).get(0);

        assertEquals(100, a.effectiveCallLimit());
        assertEquals("a", a.limitedByCall());
    }
}
