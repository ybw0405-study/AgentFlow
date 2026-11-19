package com.agentflow.workflow.audit;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 审计 outbox：写入与投递解耦。
 *
 * <p>业务线程只做一次本地 INSERT（同库、几毫秒），投递交给 {@link AuditOutboxRelay}。
 * 失败不改变 status（仍是 PENDING），只推后 next_attempt_at，因此任何时刻都不会出现
 * 「已放弃」的审计事件。
 */
@Mapper
public interface AuditOutboxMapper {

    @Insert("INSERT INTO audit_outbox"
            + " (id, tenant_id, event_type, message_json, status, attempts, next_attempt_at)"
            + " VALUES (#{id}, #{tenantId}, #{eventType}, #{messageJson}, 'PENDING', 0, NOW(3))")
    int enqueue(@Param("id") String id, @Param("tenantId") String tenantId,
                @Param("eventType") String eventType, @Param("messageJson") String messageJson);

    @Select("SELECT id, tenant_id AS tenantId, event_type AS eventType,"
            + " message_json AS messageJson, attempts, next_attempt_at AS nextAttemptAt"
            + " FROM audit_outbox WHERE status = 'PENDING' AND next_attempt_at <= NOW(3)"
            + " ORDER BY seq LIMIT #{limit}")
    List<OutboxRow> due(@Param("limit") int limit);

    @Update("UPDATE audit_outbox SET status = 'SENT', attempts = attempts + 1,"
            + " sent_at = NOW(3), last_error = NULL WHERE id = #{id}")
    int markSent(@Param("id") String id);

    @Update("UPDATE audit_outbox SET attempts = #{attempts}, last_error = #{lastError},"
            + " next_attempt_at = DATE_ADD(NOW(3), INTERVAL #{delaySeconds} SECOND)"
            + " WHERE id = #{id}")
    int markRetry(@Param("id") String id, @Param("attempts") int attempts,
                  @Param("lastError") String lastError,
                  @Param("delaySeconds") int delaySeconds);

    @Select("SELECT COUNT(*) FROM audit_outbox WHERE status = 'PENDING'")
    long pendingCount();

    @Select("SELECT MIN(created_at) FROM audit_outbox WHERE status = 'PENDING'")
    Timestamp oldestPendingAt();

    @Select("SELECT COUNT(*) FROM audit_outbox WHERE status = 'PENDING' AND attempts > 0")
    long retryingCount();

    /** 已投递记录只留一段时间：outbox 是投递队列，不是审计存储（审计在 audit_event）。 */
    @Delete("DELETE FROM audit_outbox WHERE status = 'SENT'"
            + " AND sent_at < DATE_SUB(NOW(3), INTERVAL #{days} DAY) LIMIT #{limit}")
    int purgeSent(@Param("days") int days, @Param("limit") int limit);

    record OutboxRow(String id, String tenantId, String eventType, String messageJson,
                     int attempts, Timestamp nextAttemptAt) {
    }
}
