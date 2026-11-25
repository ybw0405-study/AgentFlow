package com.agentflow.workflow.admin;

import com.agentflow.workflow.execution.BudgetLedgerMapper.BudgetLedgerRow;
import com.agentflow.workflow.execution.UsageService;
import com.agentflow.workflow.tenant.TenantMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 有效限额解析：子租户的真实上限不得高于任一祖先，取沿 parent 链的最小值。
 *
 * <p>只算数字不够用。管理员看到「SPACE 的有效限额是 5,000」时，下一个问题必然是「谁定的
 * 5,000」——如果答案在祖先上，那提高这个空间的额度是白费功夫，得去改 ORG。所以这里除了
 * 最小值，还记下**是哪个租户卡住了这一维**（{@code limitedByToken} / {@code limitedByCall}），
 * 两个维度分开记：调用与 token 的重量级可以不同，卡住它们的不一定是同一个祖先。
 *
 * <p>「无上限」用 {@code null} 表达（没有账本行，或限额字段 &lt;= 0）。不要用
 * {@code Long.MAX_VALUE} 冒充：它会一路冒到 JSON 与进度条上，前端还得反推这个魔数是什么意思。
 * 与 {@link UsageService#ratio} 的口径一致——那里同样把「没有有效限额」折算成 0 利用率。
 */
final class TenantLimitResolver {

    /** 与建租户时的层级限制一致：超过这么多层就当链条有问题，停在这里而不是转圈。 */
    private static final int MAX_DEPTH = 8;
    /** 「接近上限」的门槛，与租户自助用量页的状态判断保持同一个数。 */
    static final double NEAR_THRESHOLD = 0.8;

    private TenantLimitResolver() {
    }

    /** 一个租户在本账期内的账面情况 + 生效限额 + 限额来源。 */
    record ResolvedTenant(String tenantId, String name, String type, String parentId,
                          int callsUsed, long tokensUsed,
                          Integer ownCallLimit, Long ownTokenLimit,
                          Integer effectiveCallLimit, Long effectiveTokenLimit,
                          String limitedByCall, String limitedByCallName,
                          String limitedByToken, String limitedByTokenName,
                          boolean inherited, String state,
                          double callUtilization, double tokenUtilization) {
    }

    /**
     * @param tenants 全部租户（用来建 parent 链与取名字）
     * @param ledger 本账期的账本行（按 tenantId 索引）；没有行的租户用量记 0、限额记为无上限
     */
    static List<ResolvedTenant> resolve(List<TenantMapper.TenantRow> tenants,
                                        Map<String, BudgetLedgerRow> ledger) {
        List<ResolvedTenant> out = new ArrayList<>(tenants.size());
        for (TenantMapper.TenantRow tenant : tenants) {
            out.add(resolveOne(tenant, tenants, ledger));
        }
        return out;
    }

    static Map<String, BudgetLedgerRow> index(List<BudgetLedgerRow> ledger) {
        Map<String, BudgetLedgerRow> out = new LinkedHashMap<>();
        for (BudgetLedgerRow row : ledger) {
            out.put(row.tenantId(), row);
        }
        return out;
    }

    private static ResolvedTenant resolveOne(TenantMapper.TenantRow tenant,
                                             List<TenantMapper.TenantRow> tenants,
                                             Map<String, BudgetLedgerRow> ledger) {
        Map<String, TenantMapper.TenantRow> byId = new LinkedHashMap<>();
        for (TenantMapper.TenantRow row : tenants) {
            byId.put(row.id(), row);
        }

        BudgetLedgerRow own = ledger.get(tenant.id());
        int callsUsed = own == null ? 0 : own.callsUsed();
        long tokensUsed = own == null ? 0 : own.tokensUsed();
        Integer ownCallLimit = own == null ? null : limitOrNull(own.limitCalls());
        Long ownTokenLimit = own == null ? null : limitOrNull(own.limitTokens());

        // 自身先当基准；沿链向上，遇到更紧的就换「卡住的人」
        Integer callLimit = ownCallLimit;
        String callBy = ownCallLimit == null ? null : tenant.id();
        Long tokenLimit = ownTokenLimit;
        String tokenBy = ownTokenLimit == null ? null : tenant.id();

        String cursor = tenant.parentId();
        int depth = 0;
        while (cursor != null && depth++ < MAX_DEPTH) {
            BudgetLedgerRow ancestor = ledger.get(cursor);
            if (ancestor != null) {
                Integer ancestorCalls = limitOrNull(ancestor.limitCalls());
                if (ancestorCalls != null && (callLimit == null || ancestorCalls < callLimit)) {
                    callLimit = ancestorCalls;
                    callBy = cursor;
                }
                Long ancestorTokens = limitOrNull(ancestor.limitTokens());
                if (ancestorTokens != null && (tokenLimit == null || ancestorTokens < tokenLimit)) {
                    tokenLimit = ancestorTokens;
                    tokenBy = cursor;
                }
            }
            TenantMapper.TenantRow parent = byId.get(cursor);
            cursor = parent == null ? null : parent.parentId();
        }

        boolean inherited = differs(callBy, tenant.id()) || differs(tokenBy, tenant.id());

        return new ResolvedTenant(tenant.id(), tenant.name(), tenant.type(), tenant.parentId(),
                callsUsed, tokensUsed, ownCallLimit, ownTokenLimit,
                callLimit, tokenLimit,
                callBy, nameOf(byId, callBy), tokenBy, nameOf(byId, tokenBy),
                inherited, state(callLimit, tokenLimit, callsUsed, tokensUsed),
                UsageService.ratio(callsUsed, callLimit == null ? 0 : callLimit),
                UsageService.ratio(tokensUsed, tokenLimit == null ? 0 : tokenLimit));
    }

    /**
     * 状态给前端上色用，不替代数字：
     * {@code unbounded} 两个维度都没有限额；{@code over} 任一维度已到顶（新的执行会被拒绝）；
     * {@code near} 任一维度 ≥80%；其余 {@code ok}。
     */
    private static String state(Integer callLimit, Long tokenLimit, int callsUsed, long tokensUsed) {
        if (callLimit == null && tokenLimit == null) {
            return "unbounded";
        }
        boolean over = (callLimit != null && callsUsed >= callLimit)
                || (tokenLimit != null && tokensUsed >= tokenLimit);
        if (over) {
            return "over";
        }
        boolean near = (callLimit != null && callLimit > 0
                && (double) callsUsed / callLimit >= NEAR_THRESHOLD)
                || (tokenLimit != null && tokenLimit > 0
                && (double) tokensUsed / tokenLimit >= NEAR_THRESHOLD);
        return near ? "near" : "ok";
    }

    /** 限额字段为 0 或负数代表「没设限额」，与 ratio 的口径一致。 */
    private static Integer limitOrNull(int limit) {
        return limit <= 0 ? null : limit;
    }

    private static Long limitOrNull(long limit) {
        return limit <= 0 ? null : limit;
    }

    private static boolean differs(String id, String selfId) {
        return id != null && !id.equals(selfId);
    }

    private static String nameOf(Map<String, TenantMapper.TenantRow> byId, String id) {
        if (id == null) {
            return null;
        }
        TenantMapper.TenantRow row = byId.get(id);
        return row == null ? null : row.name();
    }
}
