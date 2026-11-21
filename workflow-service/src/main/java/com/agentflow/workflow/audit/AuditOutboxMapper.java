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
 * <p>业务线程只做一次本地 INSERT，投递交给 {@link AuditOutboxRelay}。失败不改变 status
 * （仍是 PENDING），只推后 next_attempt_at；连续失败到阈值后迁入 DEAD 隔离区，
 * 原始载荷完整保留，管理端可重放——不存在「放弃投递」，只是换成人工介入。
 */
@Mapper
public interface AuditOutboxMapper {

    @Insert("INSERT INTO audit_outbox"
            + " (id, tenant_id, event_type, message_json, status, attempts, next_attempt_at)"
            + " VALUES (#{id}, #{tenantId}, #{eventType}, #{messageJson}, 'PENDING', 0, NOW(3))")
    int enqueue(@Param("id") String id, @Param("tenantId") String tenantId,
                @Param("eventType") String eventType, @Param("messageJson") String messageJson);

    /**
     * 多副本捞同一批会重复投递（消费端幂等兜底，但白费一次出网）。
     * {@code FOR UPDATE SKIP LOCKED} 让拿到锁的副本锁住本批、其它副本跳过已锁行去捞下一批；
     * 它必须处在事务里才有效（由 {@link AuditOutboxRelay#relay()} 的 {@code @Transactional}
     * 提供），否则 autocommit 下锁在 SELECT 结束即释放，等于没加。
     */
    @Select("SELECT id, tenant_id AS tenantId, event_type AS eventType,"
            + " message_json AS messageJson, attempts, next_attempt_at AS nextAttemptAt"
            + " FROM audit_outbox WHERE status = 'PENDING' AND next_attempt_at <= NOW(3)"
            + " ORDER BY seq LIMIT #{limit} FOR UPDATE SKIP LOCKED")
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

    /** 迁入隔离区：只挡住自动重投，message_json 原样留着供人工排查与重放。 */
    @Update("UPDATE audit_outbox SET status = 'DEAD', dead_at = NOW(3),"
            + " dead_reason = #{reason}, last_error = #{lastError}"
            + " WHERE id = #{id} AND status = 'PENDING'")
    int markDead(@Param("id") String id, @Param("reason") String reason,
                 @Param("lastError") String lastError);

    /** 人工重放：清掉退避与隔离标记，下一轮 relay 会重新捞到这一行。 */
    @Update("UPDATE audit_outbox SET status = 'PENDING', attempts = 0,"
            + " next_attempt_at = NOW(3), last_error = NULL, dead_at = NULL,"
            + " dead_reason = NULL WHERE id = #{id} AND status = 'DEAD'")
    int replayDead(@Param("id") String id);

    @Select("SELECT id, tenant_id AS tenantId, event_type AS eventType,"
            + " message_json AS messageJson, attempts, dead_at AS deadAt,"
            + " dead_reason AS deadReason, last_error AS lastError,"
            + " created_at AS createdAt FROM audit_outbox WHERE status = 'DEAD'"
            + " ORDER BY dead_at LIMIT #{limit}")
    List<DeadRow> dead(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM audit_outbox WHERE status = 'PENDING'")
    long pendingCount();

    @Select("SELECT MIN(created_at) FROM audit_outbox WHERE status = 'PENDING'")
    Timestamp oldestPendingAt();

    @Select("SELECT COUNT(*) FROM audit_outbox WHERE status = 'PENDING' AND attempts > 0")
    long retryingCount();

    @Select("SELECT COUNT(*) FROM audit_outbox WHERE status = 'DEAD'")
    long deadCount();

    /** 已投递记录只留一段时间：outbox 是投递队列，不是审计存储（审计在 audit_event）。 */
    @Delete("DELETE FROM audit_outbox WHERE status = 'SENT'"
            + " AND sent_at < DATE_SUB(NOW(3), INTERVAL #{days} DAY) LIMIT #{limit}")
    int purgeSent(@Param("days") int days, @Param("limit") int limit);

    record OutboxRow(String id, String tenantId, String eventType, String messageJson,
                     int attempts, Timestamp nextAttemptAt) {
    }

    record DeadRow(String id, String tenantId, String eventType, String messageJson,
                   int attempts, Timestamp deadAt, String deadReason, String lastError,
                   Timestamp createdAt) {
    }
}
