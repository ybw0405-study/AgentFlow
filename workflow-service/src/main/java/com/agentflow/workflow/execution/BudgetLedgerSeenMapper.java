package com.agentflow.workflow.execution;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 预算账本累加的事件级幂等标记（P1-3）。
 *
 * <p>每条 EngineEvent 有全局唯一 seq。投影前先 INSERT IGNORE 占位，只有插入成功
 * （返回 1）才累加账本；重复投影同一事件时 INSERT IGNORE 返回 0，跳过累加。
 * 这把「投影不重复计费」的正确性从运维注释下沉到代码层。
 */
@Mapper
public interface BudgetLedgerSeenMapper {

    /** INSERT IGNORE：首次插入返回 1，重复 seq 返回 0。 */
    @Insert("INSERT IGNORE INTO budget_ledger_seen (event_seq, execution_id) VALUES (#{seq}, #{executionId})")
    int claim(@Param("seq") long seq, @Param("executionId") String executionId);
}
