package com.agentflow.audit.repo;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * audit_event 读写。只追加纪律（§13.3）：
 * DB 层（触发器 + 最小授权）与本接口（无 UPDATE/DELETE 方法）三重保证。
 */
@Mapper
public interface AuditEventMapper {

    @Insert("INSERT INTO audit_event"
            + " (id, tenant_id, trace_id, execution_id, actor_id, event_type,"
            + "  payload_redacted, token_used, latency_ms)"
            + " VALUES (#{id}, #{tenantId}, #{traceId}, #{executionId}, #{actorId},"
            + " #{eventType}, #{payloadJson}, #{tokenUsed}, #{latencyMs})")
    int insert(AuditRow row);

    @Select("<script>"
            + "SELECT seq, id, tenant_id AS tenantId, trace_id AS traceId,"
            + " execution_id AS executionId, actor_id AS actorId, event_type AS eventType,"
            + " payload_redacted AS payloadRedacted, token_used AS tokenUsed,"
            + " latency_ms AS latencyMs, created_at AS createdAt"
            + " FROM audit_event WHERE seq &lt; #{beforeSeq}"
            + "<if test='traceId != null'> AND trace_id = #{traceId}</if>"
            + "<if test='eventType != null'> AND event_type = #{eventType}</if>"
            + "<if test='tenantId != null'> AND tenant_id = #{tenantId}</if>"
            + " ORDER BY seq DESC LIMIT #{limit}"
            + "</script>")
    List<AuditRow> query(@Param("traceId") String traceId,
                         @Param("eventType") String eventType,
                         @Param("tenantId") String tenantId,
                         @Param("beforeSeq") long beforeSeq,
                         @Param("limit") int limit);

    record AuditRow(long seq, String id, String tenantId, String traceId, String executionId,
                    String actorId, String eventType, String payloadJson, Integer tokenUsed,
                    Integer latencyMs, Timestamp createdAt) {
    }
}
