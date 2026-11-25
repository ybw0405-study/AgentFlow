package com.agentflow.workflow.admin;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.workflow.execution.BudgetLedgerMapper;
import com.agentflow.workflow.execution.BudgetLedgerMapper.BudgetLedgerRow;
import com.agentflow.workflow.execution.NodeExecutionMapper;
import com.agentflow.workflow.execution.PreviewUsageMapper;
import com.agentflow.workflow.execution.UsageService;
import com.agentflow.workflow.execution.WorkflowExecutionMapper;
import com.agentflow.workflow.tenant.TenantMapper;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 管理端成本中心：把「账本快照」整理成能回答问题的视图。
 *
 * <p>三个问题，三种口径：
 * <ul>
 * <li>「这个租户这个月用了多少、还剩多少」——账本（budget_ledger），账期口径；</li>
 * <li>「额度是被执行吃掉的还是被调试吃掉的」——试跑明细（preview_usage）切出来；</li>
 * <li>「贵的模型被谁在用」——节点上的 model 与试跑明细，跨租户聚合。</li>
 * </ul>
 *
 * <p>整页统一使用账期口径（{@code period}），不再混入全时段数字：同一个页面上两种时间口径，
 * 读的人一定会把「这个月」和「一共」搞混。
 */
@Service
public class AdminUsageService {

    private final WorkflowExecutionMapper executions;
    private final BudgetLedgerMapper budget;
    private final PreviewUsageMapper previews;
    private final NodeExecutionMapper nodes;
    private final TenantMapper tenants;

    public AdminUsageService(WorkflowExecutionMapper executions, BudgetLedgerMapper budget,
                             PreviewUsageMapper previews, NodeExecutionMapper nodes,
                             TenantMapper tenants) {
        this.executions = executions;
        this.budget = budget;
        this.previews = previews;
        this.nodes = nodes;
        this.tenants = tenants;
    }

    public Map<String, Object> usage(String period) {
        String p = normalizePeriod(period);
        String from = p + "-01 00:00:00";

        List<BudgetLedgerRow> ledger = budget.listByPeriod(p);
        Map<String, BudgetLedgerRow> byTenant = TenantLimitResolver.index(ledger);
        Map<String, PreviewUsageMapper.PreviewTenantMonthRow> previewByTenant = new LinkedHashMap<>();
        for (var row : previews.monthByTenant(p)) {
            previewByTenant.put(row.tenantId(), row);
        }
        Map<String, long[]> execByTenant = executionStats(from);

        List<TenantLimitResolver.ResolvedTenant> resolved =
                TenantLimitResolver.resolve(tenants.listTenants(), byTenant);

        List<Map<String, Object>> rows = new ArrayList<>(resolved.size());
        for (var tenant : resolved) {
            rows.add(tenantRow(tenant, previewByTenant.get(tenant.tenantId()),
                    execByTenant.get(tenant.tenantId())));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("from", p + "-01");
        out.put("tenants", rows);
        out.put("totals", totals(rows));
        out.put("byModel", byModel(from));
        out.put("cross", cross(from, resolved));
        return out;
    }

    /**
     * 一个租户的账面行。字段名与租户自助用量页对齐（{@code callsUsed} / {@code tokensUsed} /
     * {@code tokenUtilization}），管理员与租户看到同一组数字时不该有两套叫法。
     *
     * <p>「无上限」的限额字段直接不下发（全局 {@code non_null} 序列化），前端按缺失渲染成
     * 「无上限」，而不是拿一个魔数去猜。
     */
    private Map<String, Object> tenantRow(TenantLimitResolver.ResolvedTenant tenant,
                                          PreviewUsageMapper.PreviewTenantMonthRow preview,
                                          long[] exec) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tenantId", tenant.tenantId());
        item.put("name", tenant.name());
        item.put("type", tenant.type());
        item.put("parentId", tenant.parentId());
        item.put("callsUsed", tenant.callsUsed());
        item.put("tokensUsed", tenant.tokensUsed());
        item.put("previewCalls", preview == null ? 0 : preview.calls());
        item.put("previewTokens", preview == null ? 0 : preview.tokens());
        item.put("executions", exec == null ? 0 : exec[0]);
        item.put("executionsFailed", exec == null ? 0 : exec[1]);
        item.put("ownCallLimit", tenant.ownCallLimit());
        item.put("ownTokenLimit", tenant.ownTokenLimit());
        item.put("effectiveCallLimit", tenant.effectiveCallLimit());
        item.put("effectiveTokenLimit", tenant.effectiveTokenLimit());
        item.put("limitedByCall", tenant.limitedByCall());
        item.put("limitedByCallName", tenant.limitedByCallName());
        item.put("limitedByToken", tenant.limitedByToken());
        item.put("limitedByTokenName", tenant.limitedByTokenName());
        item.put("inherited", tenant.inherited());
        item.put("state", tenant.state());
        item.put("callUtilization", tenant.callUtilization());
        item.put("tokenUtilization", tenant.tokenUtilization());
        return item;
    }

    /** 平台汇总：一眼看清「这个月平台花了多少、几个租户在超限」。 */
    private Map<String, Object> totals(List<Map<String, Object>> rows) {
        long calls = 0;
        long tokens = 0;
        long previewCalls = 0;
        long previewTokens = 0;
        long execs = 0;
        long failed = 0;
        int over = 0;
        int near = 0;
        for (var row : rows) {
            calls += asLong(row.get("callsUsed"));
            tokens += asLong(row.get("tokensUsed"));
            previewCalls += asLong(row.get("previewCalls"));
            previewTokens += asLong(row.get("previewTokens"));
            execs += asLong(row.get("executions"));
            failed += asLong(row.get("executionsFailed"));
            String state = (String) row.get("state");
            if ("over".equals(state)) {
                over++;
            } else if ("near".equals(state)) {
                near++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenants", rows.size());
        out.put("calls", calls);
        out.put("tokens", tokens);
        out.put("previewCalls", previewCalls);
        out.put("previewTokens", previewTokens);
        out.put("previewShare", share(previewTokens, tokens));
        out.put("executions", execs);
        out.put("executionsFailed", failed);
        out.put("over", over);
        out.put("near", near);
        return out;
    }

    /**
     * 跨租户按模型分解：执行侧（节点行）与试跑侧（试跑明细）在内存里按模型合并。
     *
     * <p>空模型单独一桶（非 LLM 节点、输出被溢出、历史数据），不猜、也不并进任何真实模型。
     * 「几个租户在用」取两来源的较大值：同一模型可能既被执行用过也被试跑用过，相加会
     * 把「三个租户」算成「五个租户」。
     */
    private List<Map<String, Object>> byModel(String from) {
        Map<String, long[]> series = new LinkedHashMap<>();
        for (var row : nodes.usageByModelAll(from)) {
            long[] cell = series.computeIfAbsent(keyOf(row.model()), k -> new long[4]);
            cell[0] += row.cnt();
            cell[1] += row.tokens();
            cell[2] = Math.max(cell[2], row.tenants());
        }
        for (var row : previews.byModelAll(from)) {
            long[] cell = series.computeIfAbsent(keyOf(row.model()), k -> new long[4]);
            cell[3] += row.tokens();
            cell[2] = Math.max(cell[2], row.tenants());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        series.forEach((model, cell) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", model.isEmpty() ? null : model);
            item.put("executionTokens", cell[1]);
            item.put("previewTokens", cell[3]);
            item.put("tokens", cell[1] + cell[3]);
            item.put("tenants", cell[2]);
            out.add(item);
        });
        out.sort((a, b) -> Long.compare((Long) b.get("tokens"), (Long) a.get("tokens")));
        return out;
    }

    /**
     * 「租户 × 模型」交叉表。
     *
     * <p>回答的是「A 租户在 deepseek 上花了多少」——成本中心前面按租户、按模型各看一列，
     * 但「贵模型被谁在用」只有把两维交叉起来才看得见。执行与试跑仍然在服务层合并，
     * 与 {@code byModel} 同一口径（空模型单独一列，不猜、不并进任何真实模型）。
     *
     * <p>服务端直接给出对齐好的矩阵：列是模型、行是租户、每行每列都有合计。
     * 前端算透视会引出两个真源——它算的行合计与这里的 {@code totals} 不一致时，
     * 页面上会出现「两处加起来不一样」却没人知道该信哪个。
     */
    private Map<String, Object> cross(String from,
                                      List<TenantLimitResolver.ResolvedTenant> tenants) {
        Map<String, long[]> cells = new LinkedHashMap<>();
        Map<String, long[]> modelTotals = new LinkedHashMap<>();
        Map<String, long[]> tenantTotals = new LinkedHashMap<>();
        for (var row : nodes.usageByTenantModel(from)) {
            addCell(cells, modelTotals, tenantTotals, row.tenantId(), row.model(),
                    row.tokens(), row.cnt());
        }
        for (var row : previews.byTenantModel(from)) {
            addCell(cells, modelTotals, tenantTotals, row.tenantId(), row.model(),
                    row.tokens(), row.cnt());
        }

        // 列顺序：按消费降序，空模型（未标注）永远排最后——它是「不知道」，不是「最便宜」
        List<String> models = new ArrayList<>(modelTotals.keySet());
        models.sort((a, b) -> {
            if (a.isEmpty() != b.isEmpty()) {
                return a.isEmpty() ? 1 : -1;
            }
            return Long.compare(modelTotals.get(b)[0], modelTotals.get(a)[0]);
        });

        List<Map<String, Object>> columns = new ArrayList<>(models.size());
        for (String model : models) {
            columns.add(column(model, modelTotals.get(model)));
        }

        Map<String, String> names = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (var tenant : tenants) {
            names.put(tenant.tenantId(), tenant.name());
            if (tenantTotals.containsKey(tenant.tenantId())) {
                order.add(tenant.tenantId());
            }
        }
        // 有消耗但不在租户表里的（历史数据、已删除租户）：仍然列出来，不要静默丢金额
        for (String tenantId : tenantTotals.keySet()) {
            if (!names.containsKey(tenantId)) {
                names.put(tenantId, tenantId);
                order.add(tenantId);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>(order.size());
        for (String tenantId : order) {
            List<Map<String, Object>> rowCells = new ArrayList<>(models.size());
            for (String model : models) {
                long[] cell = cells.get(cellKey(tenantId, model));
                rowCells.add(cellRow(cell));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenantId", tenantId);
            row.put("name", names.get(tenantId));
            row.put("cells", rowCells);
            row.put("tokens", tenantTotals.get(tenantId)[0]);
            row.put("calls", tenantTotals.get(tenantId)[1]);
            rows.add(row);
        }

        long[] grand = new long[2];
        for (long[] total : modelTotals.values()) {
            grand[0] += total[0];
            grand[1] += total[1];
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", columns);
        out.put("rows", rows);
        out.put("grand", cellRow(grand));
        return out;
    }

    private static void addCell(Map<String, long[]> cells, Map<String, long[]> modelTotals,
                                Map<String, long[]> tenantTotals, String tenantId, String model,
                                long tokens, long calls) {
        String key = cellKey(tenantId, keyOf(model));
        long[] cell = cells.computeIfAbsent(key, k -> new long[2]);
        cell[0] += tokens;
        cell[1] += calls;
        long[] byModel = modelTotals.computeIfAbsent(keyOf(model), k -> new long[2]);
        byModel[0] += tokens;
        byModel[1] += calls;
        long[] byTenant = tenantTotals.computeIfAbsent(tenantId, k -> new long[2]);
        byTenant[0] += tokens;
        byTenant[1] += calls;
    }

    private static Map<String, Object> column(String model, long[] total) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model.isEmpty() ? null : model);
        out.put("tokens", total[0]);
        out.put("calls", total[1]);
        return out;
    }

    private static Map<String, Object> cellRow(long[] cell) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tokens", cell == null ? 0L : cell[0]);
        out.put("calls", cell == null ? 0L : cell[1]);
        return out;
    }

    /** 租户 id 与模型名都可能含分隔符，用不可见字符拼键，避免 "a-b" + "c" 与 "a" + "b-c" 撞车。 */
    private static String cellKey(String tenantId, String model) {
        return tenantId + '\u0000' + model;
    }

    /** 每个租户的 [执行数, 失败数]，失败含 FAILED 与挂起超时后的自动裁决失败。 */
    private Map<String, long[]> executionStats(String from) {
        Map<String, long[]> out = new LinkedHashMap<>();
        for (var row : executions.usageByTenantSince(from)) {
            long[] cell = out.computeIfAbsent(row.tenantId(), k -> new long[2]);
            cell[0] += row.cnt();
            if ("FAILED".equals(row.status())) {
                cell[1] += row.cnt();
            }
        }
        return out;
    }

    /**
     * 账期只接受 {@code YYYY-MM}：写错的账期不该静默退回「本月」——管理员会以为自己看的是
     * 八月，实际看的是十一月。这里直接拒绝。
     */
    static String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return YearMonth.now().toString();
        }
        try {
            return YearMonth.parse(period.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001,
                    "period must be YYYY-MM, got: " + period);
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String keyOf(String model) {
        return model == null ? "" : model.trim();
    }

    private static double share(long part, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(part * 10_000.0 / total) / 10_000.0;
    }

    /** 与租户自助用量页共用同一口径，免得管理员看到的利用率与租户自己看到的对不上。 */
    static double ratio(long used, long limit) {
        return UsageService.ratio(used, limit);
    }
}
