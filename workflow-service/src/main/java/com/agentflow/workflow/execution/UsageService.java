package com.agentflow.workflow.execution;

import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用量记账：正式执行的消耗由投影器按 NodeCompleted 累加，试跑的消耗走这里。
 *
 * <p>为什么试跑要单独记一笔：试跑不落 {@code workflow_execution}（它是调试动作，不该出现在执行
 * 列表里），于是「调试十次」在账面上是不可见的——额度被吃掉、成本中心却一条记录都没有。
 * 这里把每次试跑写成一行明细（谁、哪个工作流、哪个节点、多少 token、多长时间），同时把
 * token 与节点数累加进同一本 {@code budget_ledger}，让月度账单是「执行 + 试跑」的真实合计。
 *
 * <p>记账失败不影响试跑本身：用户要的是节点的真实输出，账可以事后按审计补。
 */
@Service
public class UsageService {

    private static final int MAX_DAYS = 90;
    private static final int RECENT_LIMIT = 10;
    private static final int NAME_LOOKUP_LIMIT = 500;

    private final PreviewUsageMapper previews;
    private final WorkflowExecutionMapper executions;
    private final BudgetLedgerMapper budgets;
    private final WorkflowDefinitionMapper definitions;
    private final int monthlyCallLimit;
    private final long monthlyTokenLimit;

    public UsageService(PreviewUsageMapper previews, WorkflowExecutionMapper executions,
                        BudgetLedgerMapper budgets, WorkflowDefinitionMapper definitions,
                        @Value("${agentflow.tenant.monthly-call-limit:10000}") int callLimit,
                        @Value("${agentflow.tenant.monthly-token-limit:50000}") long tokenLimit) {
        this.previews = previews;
        this.executions = executions;
        this.budgets = budgets;
        this.definitions = definitions;
        this.monthlyCallLimit = callLimit;
        this.monthlyTokenLimit = tokenLimit;
    }

    /** 一次试跑的账面信息。nodeCount 是这次真实跑过的节点数（用于 calls 口径）。 */
    public record PreviewRecord(String definitionId, String nodeKey, String actorId, String status,
                                String model, int nodeCount, int tokenUsed, Integer latencyMs) {
    }

    @Transactional
    public void recordPreview(String tenantId, PreviewRecord record) {
        previews.insert(new PreviewUsageMapper.PreviewUsageRow(UUID.randomUUID().toString(),
                tenantId, record.definitionId(), record.nodeKey(), record.actorId(),
                record.status() == null ? "UNKNOWN" : truncate(record.status(), 24),
                record.model() == null ? null : truncate(record.model(), 128),
                Math.max(record.nodeCount(), 0), Math.max(record.tokenUsed(), 0),
                record.latencyMs()));
        if (record.tokenUsed() > 0 || record.nodeCount() > 0) {
            budgets.accrueByTenant(tenantId, Math.max(record.nodeCount(), 0),
                    Math.max(record.tokenUsed(), 0), monthlyCallLimit, monthlyTokenLimit);
        }
    }

    /**
     * 租户自己的用量：区间内的执行与试跑分别统计，外加本月预算余量、按工作流与按天分解。
     *
     * <p>按天序列补齐空白天（补 0），前端画柱子时不需要自己找洞。
     */
    public Map<String, Object> summary(TenantInfo tenant, int days) {
        int window = Math.min(Math.max(days, 1), MAX_DAYS);
        String tenantId = tenant.tenantId();
        LocalDate today = LocalDate.now();
        LocalDate first = today.minusDays(window - 1L);
        String from = first + " 00:00:00";

        var executionTotals = executions.usageTotals(tenantId, from);
        var previewTotals = previews.totals(tenantId, from);
        long executionTokens = executionTotals == null ? 0 : executionTotals.tokens();
        long executionCount = executionTotals == null ? 0 : executionTotals.cnt();
        long previewTokens = previewTotals == null ? 0 : previewTotals.tokens();
        long previewCount = previewTotals == null ? 0 : previewTotals.cnt();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", first.toString());
        out.put("to", today.toString());
        out.put("days", window);
        out.put("executions", totalsOf(executionCount, executionTokens));
        out.put("previews", totalsOf(previewCount, previewTokens));
        out.put("totalTokens", executionTokens + previewTokens);
        out.put("previewShare", share(previewTokens, executionTokens + previewTokens));
        out.put("budget", budgetOf(tenantId));
        out.put("byDefinition", byDefinition(tenantId, from));
        out.put("daily", daily(tenantId, from, first, window));
        out.put("recentPreviews", recent(tenantId));
        return out;
    }

    private Map<String, Object> budgetOf(String tenantId) {
        String period = YearMonth.now().toString();
        var row = budgets.find(tenantId, period).orElse(null);
        int callsUsed = row == null ? 0 : row.callsUsed();
        long tokensUsed = row == null ? 0 : row.tokensUsed();
        int callLimit = row == null ? monthlyCallLimit : row.limitCalls();
        long tokenLimit = row == null ? monthlyTokenLimit : row.limitTokens();
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("period", period);
        budget.put("callsUsed", callsUsed);
        budget.put("tokensUsed", tokensUsed);
        budget.put("callLimit", callLimit);
        budget.put("tokenLimit", tokenLimit);
        budget.put("callUtilization", ratio(callsUsed, callLimit));
        budget.put("tokenUtilization", ratio(tokensUsed, tokenLimit));
        return budget;
    }

    /** 按工作流分解：执行与试跑各自一列，让「这条流到底花在哪」一眼能看出来。 */
    private List<Map<String, Object>> byDefinition(String tenantId, String from) {
        Map<String, WorkflowExecutionMapper.DefinitionUsageRow> exec = new LinkedHashMap<>();
        for (var row : executions.usageByDefinition(tenantId, from)) {
            exec.put(row.definitionId(), row);
        }
        Map<String, PreviewUsageMapper.PreviewDefinitionRow> preview = new LinkedHashMap<>();
        for (var row : previews.byDefinition(tenantId, from)) {
            preview.put(row.definitionId(), row);
        }
        Map<String, String> names = definitionNames(tenantId);

        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(exec.keySet());
        keys.addAll(preview.keySet());
        for (String definitionId : keys) {
            var e = exec.get(definitionId);
            var p = preview.get(definitionId);
            long executionTokens = e == null ? 0 : e.tokens();
            long previewTokens = p == null ? 0 : p.tokens();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("definitionId", definitionId);
            item.put("name", names.getOrDefault(definitionId, definitionId));
            item.put("executions", e == null ? 0 : e.cnt());
            item.put("executionTokens", executionTokens);
            item.put("previews", p == null ? 0 : p.cnt());
            item.put("previewTokens", previewTokens);
            item.put("tokens", executionTokens + previewTokens);
            out.add(item);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("tokens"), (Long) a.get("tokens")));
        return out;
    }

    private List<Map<String, Object>> daily(String tenantId, String from, LocalDate first,
                                            int window) {
        Map<String, Long> exec = execDaily(executions.usageDaily(tenantId, from));
        Map<String, Long> preview = previewDaily(previews.daily(tenantId, from));
        Map<String, long[]> series = new TreeMap<>();
        for (int i = 0; i < window; i++) {
            series.put(first.plusDays(i).toString(), new long[] {0, 0});
        }
        exec.forEach((day, tokens) -> series.computeIfAbsent(day, k -> new long[] {0, 0})[0] = tokens);
        preview.forEach((day, tokens) ->
                series.computeIfAbsent(day, k -> new long[] {0, 0})[1] = tokens);

        List<Map<String, Object>> out = new ArrayList<>();
        series.forEach((day, tokens) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", day);
            item.put("executionTokens", tokens[0]);
            item.put("previewTokens", tokens[1]);
            item.put("tokens", tokens[0] + tokens[1]);
            out.add(item);
        });
        return out;
    }

    private static Map<String, Long> execDaily(List<WorkflowExecutionMapper.DailyUsageRow> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (var row : rows) {
            out.put(row.day(), row.tokens());
        }
        return out;
    }

    private static Map<String, Long> previewDaily(List<PreviewUsageMapper.PreviewDailyRow> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (var row : rows) {
            out.put(row.day(), row.tokens());
        }
        return out;
    }

    private List<Map<String, Object>> recent(String tenantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : previews.recent(tenantId, RECENT_LIMIT)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("definitionId", row.definitionId());
            item.put("definitionName", row.definitionName());
            item.put("nodeKey", row.nodeKey());
            item.put("actorId", row.actorId());
            item.put("status", row.status());
            item.put("model", row.model());
            item.put("tokenUsed", row.tokenUsed());
            item.put("latencyMs", row.latencyMs());
            item.put("createdAt", row.createdAt());
            out.add(item);
        }
        return out;
    }

    private Map<String, String> definitionNames(String tenantId) {
        Map<String, String> names = new LinkedHashMap<>();
        for (var row : definitions.listByTenant(tenantId, null, null, 0L, NAME_LOOKUP_LIMIT)) {
            names.put(row.id(), row.name());
        }
        return names;
    }

    private static Map<String, Object> totalsOf(long count, long tokens) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("count", count);
        item.put("tokens", tokens);
        return item;
    }

    private static double share(long part, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(part * 10_000.0 / total) / 10_000.0;
    }

    /**
     * 用量占比，落在 [0,1]：超额后不再线性增长。
     *
     * <p>这个字段是给进度条与「是否接近上限」判断用的，超过 1 只会让调用方自己再夹一次；
     * 真实超额情况看原始的使用量与限额字段。
     */
    public static double ratio(long used, long limit) {
        if (limit <= 0 || limit == Long.MAX_VALUE || limit == Integer.MAX_VALUE) {
            return 0.0;
        }
        return Math.min(1.0, Math.round(used * 10_000.0 / limit) / 10_000.0);
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
