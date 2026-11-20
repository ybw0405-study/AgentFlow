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
        Mockito.when(nodes.usageByTenantModel(Mockito.anyString())).thenReturn(List.of(
                new NodeExecutionMapper.TenantModelRow("space", "deepseek-chat", 20, 8_000),
                new NodeExecutionMapper.TenantModelRow("org", "qwen-max", 3, 1_200),
                new NodeExecutionMapper.TenantModelRow("space", "", 4, 40)));
        Mockito.when(previews.byTenantModel(Mockito.anyString())).thenReturn(List.of(
                new PreviewUsageMapper.PreviewTenantModelRow("space", "deepseek-chat", 5, 300)));
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
    @SuppressWarnings("unchecked")
    void crossPivotsTenantByModelWithAlignedTotals() {
        var data = service.usage("2026-11");
        var cross = (Map<String, Object>) data.get("cross");
        var models = (List<Map<String, Object>>) cross.get("models");
        var matrixRows = (List<Map<String, Object>>) cross.get("rows");

        // 列按消费降序，未标注永远最后：它是「不知道」，不是「最便宜」
        assertEquals(java.util.Arrays.asList("deepseek-chat", "qwen-max", null),
                models.stream().map(m -> m.get("model")).toList());
        // 试跑与执行合并到同一格：8000 + 300
        assertEquals(8_300L, models.get(0).get("tokens"));
        assertEquals(25L, models.get(0).get("calls"));
        assertEquals(1_200L, models.get(1).get("tokens"));
        assertEquals(40L, models.get(2).get("tokens"));

        // 行按租户表顺序，没消耗的租户不占一行
        assertEquals(List.of("org", "space"), matrixRows.stream().map(r -> r.get("tenantId")).toList());
        var orgCells = (List<Map<String, Object>>) matrixRows.get(0).get("cells");
        var spaceCells = (List<Map<String, Object>>) matrixRows.get(1).get("cells");
        // 每行的格子数与列数对齐，缺失的格子是 0 而不是错位
        assertEquals(3, orgCells.size());
        assertEquals(0L, orgCells.get(0).get("tokens"));
        assertEquals(1_200L, orgCells.get(1).get("tokens"));
        assertEquals(8_300L, spaceCells.get(0).get("tokens"));
        assertEquals(40L, spaceCells.get(2).get("tokens"));

        // 行列合计一致：这是交叉表唯一的正确性断言
        long rowSum = matrixRows.stream().mapToLong(r -> (Long) r.get("tokens")).sum();
        long colSum = models.stream().mapToLong(m -> (Long) m.get("tokens")).sum();
        var grand = (Map<String, Object>) cross.get("grand");
        assertEquals(colSum, rowSum);
        assertEquals(grand.get("tokens"), rowSum);
    }

    @Test
    @SuppressWarnings("unchecked")
    void crossKeepsTenantsThatNoLongerExistInTheTenantTable() {
        Mockito.when(nodes.usageByTenantModel(Mockito.anyString())).thenReturn(List.of(
                new NodeExecutionMapper.TenantModelRow("ghost", "deepseek-chat", 1, 777)));
        Mockito.when(previews.byTenantModel(Mockito.anyString())).thenReturn(List.of());

        var cross = (Map<String, Object>) service.usage("2026-11").get("cross");
        var matrixRows = (List<Map<String, Object>>) cross.get("rows");

        // 已删除租户的历史消耗仍然要出现：静默丢一行等于账对不上
        assertEquals(1, matrixRows.size());
        assertEquals("ghost", matrixRows.get(0).get("tenantId"));
        assertEquals("ghost", matrixRows.get(0).get("name"));
        assertEquals(777L, matrixRows.get(0).get("tokens"));
    }

    @Test
    void ratioMatchesTenantFacingUtilization() {
        // 与租户自助用量页同一口径：超额夹到 1，无上限时为 0
        assertEquals(0.5, AdminUsageService.ratio(500, 1_000));
        assertEquals(1.0, AdminUsageService.ratio(1_500, 1_000));
        assertEquals(0.0, AdminUsageService.ratio(900, 0));
    }
}
