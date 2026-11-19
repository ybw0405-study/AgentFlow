package com.agentflow.workflow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 直写出口：跳过 MQ，直接追加到 {@code audit_event}（与 connector-service 同一做法）。
 *
 * <p>两个用途：其一，未部署 MQ 的环境（本地演示、单机部署）审计也能留痕；其二，
 * relay 连续投递 MQ 失败时降级到这条通道——审计是治理要求，宁可少一次 MQ 扇出，
 * 也不能因为 broker 挂了就没有记录。
 *
 * <p>幂等靠主键（消息 id 由发送方生成）：重复投递命中 DuplicateKeyException 视为已送达。
 * 注意 audit_event 有 append-only 触发器（禁 UPDATE/DELETE），所以这里只能 INSERT，
 * 不能用 ON DUPLICATE KEY UPDATE。
 */
@Component
public class DirectAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(DirectAuditSink.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public DirectAuditSink(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "direct:audit_event";
    }

    @Override
    public void deliver(AuditMessage msg) throws Exception {
        try {
            jdbc.update("INSERT INTO audit_event (id, tenant_id, trace_id, execution_id,"
                            + " actor_id, event_type, payload_redacted, token_used, latency_ms)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    msg.id(), msg.tenantId(),
                    msg.traceId() == null ? "-" : msg.traceId(),
                    msg.executionId(), msg.actorId(), msg.eventType(),
                    msg.payloadRedacted() == null ? null
                            : JSON.writeValueAsString(msg.payloadRedacted()),
                    msg.tokenUsed(), msg.latencyMs());
        } catch (DuplicateKeyException e) {
            log.debug("audit already persisted id={} type={}", msg.id(), msg.eventType());
        }
    }
}
