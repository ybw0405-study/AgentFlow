package com.agentflow.workflow.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.workflow.execution.BudgetLedgerMapper;
import com.agentflow.workflow.execution.NodeExecutionMapper;
import com.agentflow.workflow.execution.PreviewUsageMapper;
import com.agentflow.workflow.execution.WorkflowExecutionMapper;
import com.agentflow.workflow.tenant.TenantMapper;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 管理端成本中心：汇总口径、账期校验、跨租户按模型合并。 */
class AdminUsageServiceTest {

    private WorkflowExecutionMapper executions;
    private BudgetLedgerMapper budget;
    private PreviewUsageMapper previews;
    private NodeExecutionMapper nodes;
    private TenantMapper tenants;
    private AdminUsageService service;

    @BeforeEach
    void setUp() {
        executions = Mockito.mock(WorkflowExecutionMapper.class);
        budget = Mockito.mock(BudgetLedgerMapper.class);
        previews = Mockito.mock(PreviewUsageMapper.class);
        nodes = Mockito.mock(NodeExecutionMapper.class);
        tenants = Mockito.mock(TenantMapper.class);
        service = new AdminUsageService(executions, budget, previews, nodes, tenants);

        Mockito.when(tenants.listTenants()).thenReturn(List.of(
                new TenantMapper.TenantRow("org", "Org", null, "ORG"),
                new TenantMapper.TenantRow("space", "Space", "org", "SPACE")));
        Mockito.when(budget.listByPeriod("2026-11")).thenReturn(List.of(
                new BudgetLedgerMapper.BudgetLedgerRow("org", "2026-11", 0, 0, 1_000, 10_000),
                new BudgetLedgerMapper.BudgetLedgerRow("space", "2026-11", 900, 9_500, 9_000, 90_000)));
        Mockito.when(previews.monthByTenant("2026-11")).thenReturn(List.of(
                new PreviewUsageMapper.PreviewTenantMonthRow("space", 40, 500)));
        Mockito.when(executions.usageByTenantSince(Mockito.anyString())).thenReturn(List.of(
                new WorkflowExecutionMapper.UsageRow("space", "SUCCEEDED", 8, 9_000),
                new WorkflowExecutionMapper.UsageRow("space", "FAILED", 2, 500)));
        Mockito.when(nodes.usageByModelAll(Mockito.anyString())).thenReturn(List.of(
                new NodeExecutionMapper.PlatformModelRow("deepseek-chat", 20, 8_000, 3),
                new NodeExecutionMapper.PlatformModelRow("", 4, 40, 2)));
        Mockito.when(previews.byModelAll(Mockito.anyString())).thenReturn(List.of(
                new PreviewUsageMapper.PlatformPreviewModelRow("deepseek-chat", 5, 300, 5)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("tenants");
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String tenantId) {
        return rows.stream().filter(r -> tenantId.equals(r.get("tenantId"))).findFirst().orElseThrow();
    }

    @Test
    void periodIsNormalizedAndEmptyFallsBackToCurrentMonth() {
        // `2026-8` 不是合法的 YearMonth 写法；静默当成本月会让管理员看错月份
        assertEquals(YearMonth.now().toString(), AdminUsageService.normalizePeriod(null));
        assertEquals(YearMonth.now().toString(), AdminUsageService.normalizePeriod("  "));
        assertEquals("2026-11", AdminUsageService.normalizePeriod("2026-11"));

        var error = assertThrows(BusinessException.class,
                () -> AdminUsageService.normalizePeriod("2026-8"));
        assertEquals(ErrorCode.COM_VALIDATION_001, error.errorCode());
    }

    @Test
    void usageCarriesEffectiveLimitsAndExecutionStats() {
        var data = service.usage("2026-11");
        var space = row(rows(data), "space");

        assertEquals("Space", space.get("name"));
        assertEquals(900, space.get("callsUsed"));
        assertEquals(9_500L, space.get("tokensUsed"));
        assertEquals(40L, space.get("previewCalls"));
        assertEquals(500L, space.get("previewTokens"));
        // 执行数与失败数按账期口径，失败只数 FAILED
        assertEquals(10L, space.get("executions"));
        assertEquals(2L, space.get("executionsFailed"));
        // 自身写着 9,000，真实上限是父的 1,000
        assertEquals(9_000, space.get("ownCallLimit"));
        assertEquals(1_000, space.get("effectiveCallLimit"));
        assertEquals("org", space.get("limitedByToken"));
        assertEquals("Org", space.get("limitedByTokenName"));
        assertEquals(Boolean.TRUE, space.get("inherited"));
        assertEquals("near", space.get("state"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void totalsSumTenantsAndCountQuotaStates() {
        var data = service.usage("2026-11");
        var totals = (Map<String, Object>) data.get("totals");

        assertEquals(2, totals.get("tenants"));
        assertEquals(900L, totals.get("calls"));
        assertEquals(9_500L, totals.get("tokens"));
        assertEquals(40L, totals.get("previewCalls"));
        assertEquals(500L, totals.get("previewTokens"));
        assertEquals(0.0526, (Double) totals.get("previewShare"), 1e-9);
        assertEquals(10L, totals.get("executions"));
        assertEquals(2L, totals.get("executionsFailed"));
        // space 的 900/1000 与 9500/10000 都是 90%+，org 还没用量：只有 1 个接近上限
        assertEquals(1, totals.get("near"));
        assertEquals(0, totals.get("over"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void byModelMergesSourcesAndCountsTenantsOnce() {
        var data = service.usage("2026-11");
        var byModel = (List<Map<String, Object>>) data.get("byModel");

        assertEquals(2, byModel.size());
        var chat = byModel.get(0);
        assertEquals("deepseek-chat", chat.get("model"));
        assertEquals(8_000L, chat.get("executionTokens"));
        assertEquals(300L, chat.get("previewTokens"));
        assertEquals(8_300L, chat.get("tokens"));
        // 执行侧 3 个租户、试跑侧 5 个：并集最多 5，不能相加成 8
        assertEquals(5L, chat.get("tenants"));

        // 空模型单独一桶，不并进真实模型
        assertNull(byModel.get(1).get("model"));
        assertEquals(40L, byModel.get(1).get("tokens"));
    }

    @Test
    void tenantWithoutLedgerRowHasNoLimitFields() {
        Mockito.when(budget.listByPeriod("2026-11")).thenReturn(List.of());

        var org = row(rows(service.usage("2026-11")), "org");

        // 无上限的字段直接不下发（序列化时会被丢掉），前端按缺失渲染
        assertNull(org.get("effectiveCallLimit"));
        assertNull(org.get("effectiveTokenLimit"));
        assertFalse((Boolean) org.get("inherited"));
        assertEquals("unbounded", org.get("state"));
        assertTrue((Long) org.get("previewTokens") == 0L);
    }

    @Test
    void ratioMatchesTenantFacingUtilization() {
        // 与租户自助用量页同一口径：超额夹到 1，无上限时为 0
        assertEquals(0.5, AdminUsageService.ratio(500, 1_000));
        assertEquals(1.0, AdminUsageService.ratio(1_500, 1_000));
        assertEquals(0.0, AdminUsageService.ratio(900, 0));
    }
}
