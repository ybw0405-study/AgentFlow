package com.agentflow.workflow.gate;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 裁决 outbox：引擎不可达时先落库，由 relay 重投。
 *
 * <p>为什么不让用户重试：审批是「外部动作」，用户已经点了按钮，系统不能把可靠性
 * 转嫁给用户；重复投递在引擎侧是幂等的（节点已终态 → ALREADY_RESOLVED）。
 */
@Mapper
public interface GateResolveOutboxMapper {

    @Insert("INSERT INTO gate_resolve_outbox"
            + " (id, execution_id, node_key, decision, resolved_by, comment, status,"
            + " attempts, next_attempt_at)"
            + " VALUES (#{id}, #{executionId}, #{nodeKey}, #{decision}, #{resolvedBy},"
            + " #{comment}, 'PENDING', 0, NOW(3))"
            + " ON DUPLICATE KEY UPDATE decision = VALUES(decision),"
            + " resolved_by = VALUES(resolved_by), comment = VALUES(comment),"
            + " status = 'PENDING', next_attempt_at = NOW(3)")
    int enqueue(OutboxRow row);

    @Select("SELECT id, execution_id AS executionId, node_key AS nodeKey, decision,"
            + " resolved_by AS resolvedBy, comment, status, attempts,"
            + " next_attempt_at AS nextAttemptAt, last_error AS lastError"
            + " FROM gate_resolve_outbox WHERE status = 'PENDING'"
            + " AND next_attempt_at <= NOW(3) ORDER BY next_attempt_at LIMIT #{limit}")
    List<OutboxRow> due(@Param("limit") int limit);

    @Update("UPDATE gate_resolve_outbox SET status = 'DONE', attempts = attempts + 1"
            + " WHERE id = #{id}")
    int markDone(@Param("id") String id);

    @Update("UPDATE gate_resolve_outbox SET status = #{status}, attempts = #{attempts},"
            + " last_error = #{lastError},"
            + " next_attempt_at = DATE_ADD(NOW(3), INTERVAL #{delaySeconds} SECOND)"
            + " WHERE id = #{id}")
    int markRetry(@Param("id") String id, @Param("status") String status,
                  @Param("attempts") int attempts, @Param("lastError") String lastError,
                  @Param("delaySeconds") int delaySeconds);

    record OutboxRow(String id, String executionId, String nodeKey, String decision,
                     String resolvedBy, String comment, String status, int attempts,
                     Timestamp nextAttemptAt, String lastError) {
    }
}
