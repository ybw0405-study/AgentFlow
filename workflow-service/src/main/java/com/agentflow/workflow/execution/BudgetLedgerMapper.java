package com.agentflow.workflow.execution;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 成本中心账本（budget_ledger）：按租户 + 账期累加调用数与 token 消耗。
 * 累加由投影器在 NodeCompleted 事件上驱动，与游标推进同事务，故事件重放不会重复计数。
 */
@Mapper
public interface BudgetLedgerMapper {

    @Update("INSERT INTO budget_ledger (tenant_id, period, calls_used, tokens_used,"
            + " limit_calls, limit_tokens)"
            + " SELECT e.tenant_id, DATE_FORMAT(NOW(3), '%Y-%m'), 1, #{tokens},"
            + " #{limitCalls}, #{limitTokens}"
            + " FROM workflow_execution e WHERE e.id = #{executionId}"
            + " ON DUPLICATE KEY UPDATE calls_used = calls_used + 1,"
            + " tokens_used = tokens_used + #{tokens}")
    int accrue(@Param("executionId") String executionId, @Param("tokens") int tokens,
               @Param("limitCalls") int limitCalls, @Param("limitTokens") long limitTokens);

    @Select("SELECT tenant_id AS tenantId, period, calls_used AS callsUsed,"
            + " tokens_used AS tokensUsed, limit_calls AS limitCalls,"
            + " limit_tokens AS limitTokens FROM budget_ledger WHERE period = #{period}"
            + " ORDER BY tenant_id")
    List<BudgetLedgerRow> listByPeriod(@Param("period") String period);

    @Select("SELECT tenant_id AS tenantId, period, calls_used AS callsUsed,"
            + " tokens_used AS tokensUsed, limit_calls AS limitCalls,"
            + " limit_tokens AS limitTokens FROM budget_ledger"
            + " WHERE tenant_id = #{tenantId} AND period = #{period}")
    Optional<BudgetLedgerRow> find(@Param("tenantId") String tenantId,
                                   @Param("period") String period);

    record BudgetLedgerRow(String tenantId, String period, int callsUsed, long tokensUsed,
                           int limitCalls, long limitTokens) {
    }
}
