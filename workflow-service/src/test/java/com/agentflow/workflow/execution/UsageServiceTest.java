package com.agentflow.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 用量口径：执行与试跑分列、按天补齐空白、按工作流合并名称。 */
class UsageServiceTest {

    private PreviewUsageMapper previews;
    private WorkflowExecutionMapper executions;
    private NodeExecutionMapper nodes;
    private BudgetLedgerMapper budgets;
    private WorkflowDefinitionMapper definitions;
    private UsageService service;

    private final TenantInfo tenant = new TenantInfo("space-1", "user-1", "EDITOR");

    @BeforeEach
    void setUp() {
        previews = Mockito.mock(PreviewUsageMapper.class);
        executions = Mockito.mock(WorkflowExecutionMapper.class);
        nodes = Mockito.mock(NodeExecutionMapper.class);
        budgets = Mockito.mock(BudgetLedgerMapper.class);
        definitions = Mockito.mock(WorkflowDefinitionMapper.class);
        service = new UsageService(previews, executions, nodes, budgets, definitions, 100, 1_000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarySplitsExecutionAndPreviewSpend() {
        when(executions.usageTotals(anyString(), anyString()))
                .thenReturn(new WorkflowExecutionMapper.TotalsRow(3, 300));
        when(previews.totals(anyString(), anyString()))
                .thenReturn(new PreviewUsageMapper.PreviewTotalsRow(2, 60, 4));
        when(budgets.find(eq("space-1"), anyString())).thenReturn(Optional.of(
                new BudgetLedgerMapper.BudgetLedgerRow("space-1", "2026-11", 7, 360, 100, 1_000)));
        when(executions.usageByDefinition(anyString(), anyString()))
                .thenReturn(List.of(new WorkflowExecutionMapper.DefinitionUsageRow("d1", 3, 300)));
        when(previews.byDefinition(anyString(), anyString())).thenReturn(List.of(
                new PreviewUsageMapper.PreviewDefinitionRow("d1", 2, 60),
                new PreviewUsageMapper.PreviewDefinitionRow("d2", 1, 10)));
        when(definitions.listByTenant(anyString(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of(definition("d1", "客服问答"), definition("d2", "日报生成")));
        when(executions.usageDaily(anyString(), anyString())).thenReturn(
                List.of(new WorkflowExecutionMapper.DailyUsageRow(
                        LocalDate.now().toString(), 300)));
        when(previews.daily(anyString(), anyString())).thenReturn(List.of());
        when(previews.monthToDate(anyString(), anyString()))
                .thenReturn(new PreviewUsageMapper.PreviewMonthRow(4, 60));
        when(nodes.usageByModel(anyString(), anyString())).thenReturn(
                List.of(new NodeExecutionMapper.ModelUsageRow("deepseek-chat", 3, 300)));
        when(previews.byModel(anyString(), anyString())).thenReturn(List.of(
                new PreviewUsageMapper.PreviewModelRow("deepseek-chat", 2, 50),
                new PreviewUsageMapper.PreviewModelRow("", 1, 10)));

        Map<String, Object> summary = service.summary(tenant, 3);

        Map<String, Object> execution = (Map<String, Object>) summary.get("executions");
        Map<String, Object> preview = (Map<String, Object>) summary.get("previews");
        assertEquals(3L, execution.get("count"));
        assertEquals(300L, execution.get("tokens"));
        assertEquals(2L, preview.get("count"));
        assertEquals(60L, preview.get("tokens"));
        assertEquals(360L, summary.get("totalTokens"));
        assertEquals(0.1667, summary.get("previewShare"));

        Map<String, Object> budget = (Map<String, Object>) summary.get("budget");
        assertEquals(360L, budget.get("tokensUsed"));
        assertEquals(0.36, budget.get("tokenUtilization"));
        // 试跑是账本的一部分，但进度条要能单独指出它的份额
        assertEquals(4L, budget.get("previewCalls"));
        assertEquals(60L, budget.get("previewTokens"));
        assertEquals(0.06, budget.get("previewTokenUtilization"));

        List<Map<String, Object>> byDefinition =
                (List<Map<String, Object>>) summary.get("byDefinition");
        assertEquals(2, byDefinition.size());
        assertEquals("客服问答", byDefinition.get(0).get("name"));
        assertEquals(360L, byDefinition.get(0).get("tokens"));
        assertEquals("日报生成", byDefinition.get(1).get("name"));
        assertEquals(10L, byDefinition.get(1).get("tokens"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailySeriesHasOnePointPerDayEvenWithoutTraffic() {
        when(executions.usageTotals(anyString(), anyString()))
                .thenReturn(new WorkflowExecutionMapper.TotalsRow(0, 0));
        when(previews.totals(anyString(), anyString()))
                .thenReturn(new PreviewUsageMapper.PreviewTotalsRow(0, 0, 0));
        when(budgets.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(executions.usageByDefinition(anyString(), anyString())).thenReturn(List.of());
        when(previews.byDefinition(anyString(), anyString())).thenReturn(List.of());
        when(definitions.listByTenant(anyString(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(executions.usageDaily(anyString(), anyString())).thenReturn(List.of());
        when(previews.daily(anyString(), anyString())).thenReturn(List.of(
                new PreviewUsageMapper.PreviewDailyRow(LocalDate.now().toString(), 42)));

        Map<String, Object> summary = service.summary(tenant, 5);

        List<Map<String, Object>> daily = (List<Map<String, Object>>) summary.get("daily");
        assertEquals(5, daily.size());
        assertEquals(LocalDate.now().minusDays(4).toString(), daily.get(0).get("date"));
        assertEquals(0L, daily.get(0).get("tokens"));
        assertEquals(42L, daily.get(4).get("previewTokens"));
        // 没有账本行时用配置的默认限额，不能让前端的进度条因为空行而除以零
        Map<String, Object> budget = (Map<String, Object>) summary.get("budget");
        assertEquals(100, budget.get("callLimit"));
        assertEquals(1_000L, budget.get("tokenLimit"));
        assertEquals(0.0, budget.get("tokenUtilization"));
    }

    @Test
    void summaryClampsTheWindow() {
        when(executions.usageTotals(anyString(), anyString()))
                .thenReturn(new WorkflowExecutionMapper.TotalsRow(0, 0));
        when(previews.totals(anyString(), anyString()))
                .thenReturn(new PreviewUsageMapper.PreviewTotalsRow(0, 0, 0));
        when(budgets.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(executions.usageByDefinition(anyString(), anyString())).thenReturn(List.of());
        when(previews.byDefinition(anyString(), anyString())).thenReturn(List.of());
        when(definitions.listByTenant(anyString(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(executions.usageDaily(anyString(), anyString())).thenReturn(List.of());
        when(previews.daily(anyString(), anyString())).thenReturn(List.of());

        assertEquals(90, service.summary(tenant, 100_000).get("days"));
        assertEquals(1, service.summary(tenant, 0).get("days"));
    }

    @Test
    void recordPreviewWritesDetailAndAccruesBudget() {
        service.recordPreview("space-1", new UsageService.PreviewRecord(
                "d1", "draft", "user-1", "SUCCEEDED", "deepseek-chat", 3, 120, 900));

        verify(previews).insert(any(PreviewUsageMapper.PreviewUsageRow.class));
        verify(budgets).accrueByTenant("space-1", 3, 120, 100, 1_000);
    }

    @Test
    void recordPreviewSwallowsNegativeNumbersFromUpstream() {
        service.recordPreview("space-1", new UsageService.PreviewRecord(
                "d1", "draft", "user-1", null, null, -1, -5, null));

        var captor = org.mockito.ArgumentCaptor.forClass(PreviewUsageMapper.PreviewUsageRow.class);
        verify(previews).insert(captor.capture());
        assertEquals(0, captor.getValue().tokenUsed());
        assertEquals(0, captor.getValue().nodeCount());
        assertEquals("UNKNOWN", captor.getValue().status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void byModelMergesExecutionsAndPreviewsAndKeepsUnknownSeparate() {
        when(executions.usageTotals(anyString(), anyString()))
                .thenReturn(new WorkflowExecutionMapper.TotalsRow(0, 0));
        when(previews.totals(anyString(), anyString()))
                .thenReturn(new PreviewUsageMapper.PreviewTotalsRow(0, 0, 0));
        when(budgets.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(previews.monthToDate(anyString(), anyString())).thenReturn(null);
        when(executions.usageByDefinition(anyString(), anyString())).thenReturn(List.of());
        when(previews.byDefinition(anyString(), anyString())).thenReturn(List.of());
        when(definitions.listByTenant(anyString(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(executions.usageDaily(anyString(), anyString())).thenReturn(List.of());
        when(previews.daily(anyString(), anyString())).thenReturn(List.of());
        when(nodes.usageByModel(anyString(), anyString())).thenReturn(List.of(
                new NodeExecutionMapper.ModelUsageRow("deepseek-chat", 5, 500),
                new NodeExecutionMapper.ModelUsageRow("", 2, 20)));
        when(previews.byModel(anyString(), anyString())).thenReturn(List.of(
                new PreviewUsageMapper.PreviewModelRow("deepseek-chat", 3, 90),
                new PreviewUsageMapper.PreviewModelRow(null, 1, 5)));

        Map<String, Object> summary = service.summary(tenant, 30);
        List<Map<String, Object>> byModel = (List<Map<String, Object>>) summary.get("byModel");

        assertEquals(2, byModel.size(), "同一模型在两个来源里要合并成一行");
        assertEquals("deepseek-chat", byModel.get(0).get("model"));
        assertEquals(5L, byModel.get(0).get("executions"));
        assertEquals(500L, byModel.get(0).get("executionTokens"));
        assertEquals(3L, byModel.get(0).get("previews"));
        assertEquals(90L, byModel.get(0).get("previewTokens"));
        assertEquals(590L, byModel.get(0).get("tokens"));
        // 没有模型的节点（非 LLM / 输出溢出）单独一桶，不能并进真实模型
        assertNull(byModel.get(1).get("model"));
        assertEquals(20L, byModel.get(1).get("executionTokens"));
        assertEquals(5L, byModel.get(1).get("previewTokens"));
        // 按合计倒序：最贵的排最上面
        assertEquals(25L, byModel.get(1).get("tokens"));
    }

    private static WorkflowDefinitionMapper.DefinitionRow definition(String id, String name) {
        return new WorkflowDefinitionMapper.DefinitionRow(1L, id, "space-1", name, null,
                "DRAFT", null, 0, "{}", null, "user-1", null, null);
    }

    @Test
    void ratioStaysWithinUnitInterval() {
        assertNotNull(UsageService.ratio(1, 2));
        assertEquals(0.5, UsageService.ratio(1, 2));
        assertEquals(1.0, UsageService.ratio(9, 2));
        assertEquals(0.0, UsageService.ratio(9, 0));
    }
}
