package com.agentflow.workflow.admin;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AuthenticatedUser;
import com.agentflow.workflow.execution.BudgetLedgerMapper;
import com.agentflow.workflow.execution.BudgetLedgerMapper.BudgetLedgerRow;
import com.agentflow.workflow.execution.WorkflowExecutionMapper;
import com.agentflow.workflow.security.JwtAuthFilter;
import com.agentflow.workflow.tenant.TenantMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：成本中心（平台 ADMIN）。
 *
 * <p>用量来自 budget_ledger（投影器按 NodeCompleted 累加）；同时给出「有效限额」——
 * 子租户的限额不得高于任一祖先（取沿链最小值），即三级继承下的真实上限。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminUsageController {

    private final WorkflowExecutionMapper executions;
    private final BudgetLedgerMapper budget;
    private final TenantMapper tenants;

    public AdminUsageController(WorkflowExecutionMapper executions, BudgetLedgerMapper budget,
                                TenantMapper tenants) {
        this.executions = executions;
        this.budget = budget;
        this.tenants = tenants;
    }

    @GetMapping("/usage")
    public ApiResponse<Map<String, Object>> usage(HttpServletRequest request,
                                                  @RequestParam(required = false)
                                                  String period) {
        var user = (AuthenticatedUser) request.getAttribute(JwtAuthFilter.ATTR_USER);
        if (user == null || !user.isAdmin()) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "platform admin required");
        }
        String p = period != null ? period : YearMonth.now().toString();
        List<BudgetLedgerRow> ledger = budget.listByPeriod(p);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);
        result.put("budgetLedger", ledger);
        result.put("effectiveLimits", effectiveLimits(ledger));
        result.put("executions", executions.usageByTenant());
        return ApiResponse.ok(result);
    }

    /** 沿 parent 链取最小限额；自身无账本行时用量记 0。 */
    private List<Map<String, Object>> effectiveLimits(List<BudgetLedgerRow> ledger) {
        Map<String, TenantMapper.TenantRow> tree = new LinkedHashMap<>();
        for (TenantMapper.TenantRow row : tenants.listTenants()) {
            tree.put(row.id(), row);
        }
        Map<String, BudgetLedgerRow> usage = new LinkedHashMap<>();
        for (BudgetLedgerRow row : ledger) {
            usage.put(row.tenantId(), row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TenantMapper.TenantRow tenant : tree.values()) {
            BudgetLedgerRow row = usage.get(tenant.id());
            long tokenLimit = row == null ? Long.MAX_VALUE : row.limitTokens();
            int callLimit = row == null ? Integer.MAX_VALUE : row.limitCalls();
            String cursor = tenant.parentId();
            int depth = 0;
            while (cursor != null && depth++ < 8) {
                BudgetLedgerRow ancestor = usage.get(cursor);
                if (ancestor != null) {
                    tokenLimit = Math.min(tokenLimit, ancestor.limitTokens());
                    callLimit = Math.min(callLimit, ancestor.limitCalls());
                }
                TenantMapper.TenantRow parent = tree.get(cursor);
                cursor = parent == null ? null : parent.parentId();
            }
            long tokensUsed = row == null ? 0 : row.tokensUsed();
            int callsUsed = row == null ? 0 : row.callsUsed();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tenantId", tenant.id());
            item.put("name", tenant.name());
            item.put("type", tenant.type());
            item.put("parentId", tenant.parentId());
            item.put("callsUsed", callsUsed);
            item.put("tokensUsed", tokensUsed);
            item.put("effectiveCallLimit", callLimit);
            item.put("effectiveTokenLimit", tokenLimit);
            item.put("callUtilization", ratio(callsUsed, callLimit));
            item.put("tokenUtilization", ratio(tokensUsed, tokenLimit));
            out.add(item);
        }
        return out;
    }

    private static double ratio(long used, long limit) {
        if (limit <= 0 || limit == Long.MAX_VALUE || limit == Integer.MAX_VALUE) {
            return 0.0;
        }
        return Math.round(used * 10_000.0 / limit) / 10_000.0;
    }
}
