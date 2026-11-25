package com.agentflow.connector.service;

import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 连接器变更审计：直接写 audit_event（只追加表）。
 * payload 只放标识与类型，绝不含密钥；写入失败不阻断业务（降级为日志）。
 */
@Component
public class ConnectorAudit {

    private static final Logger log = LoggerFactory.getLogger(ConnectorAudit.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ConnectorAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(TenantInfo tenant, String traceId, String eventType,
                       Map<String, Object> payload) {
        try {
            jdbc.update("INSERT INTO audit_event (id, tenant_id, trace_id, actor_id,"
                            + " event_type, payload_redacted) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), tenant.tenantId(),
                    traceId == null ? "-" : traceId, tenant.userId(), eventType,
                    JSON.writeValueAsString(payload));
        } catch (Exception e) {
            // 凭据治理事件（CONN_AUTHORIZED / CONN_OAUTH_GRANTED 等）不能静默丢：
            // 直写失败时入 outbox，由 relay 补投。
            log.error("connector audit direct write failed; enqueue outbox: {}", e.toString());
            enqueueOutbox(tenant, traceId, eventType, payload);
        }
    }

    private void enqueueOutbox(TenantInfo tenant, String traceId, String eventType,
                               Map<String, Object> payload) {
        String id = UUID.randomUUID().toString();
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", id);
            message.put("tenantId", tenant.tenantId());
            message.put("traceId", traceId == null ? "-" : traceId);
            message.put("executionId", null);
            message.put("actorId", tenant.userId());
            message.put("eventType", eventType);
            message.put("payloadRedacted", payload);
            message.put("tokenUsed", null);
            message.put("latencyMs", null);
            message.put("occurredAt", java.time.Instant.now().toString());
            jdbc.update("INSERT INTO audit_outbox"
                            + " (id, tenant_id, event_type, message_json, status, attempts, next_attempt_at)"
                            + " VALUES (?,?,?,?,'PENDING',0,NOW(3))",
                    id, tenant.tenantId(), eventType, JSON.writeValueAsString(message));
        } catch (Exception fallback) {
            log.error("connector audit LOST id={} type={} tenant={}", id, eventType,
                    tenant.tenantId(), fallback);
        }
    }
}
