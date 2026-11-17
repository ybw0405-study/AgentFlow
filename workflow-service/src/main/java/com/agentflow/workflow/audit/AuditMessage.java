package com.agentflow.workflow.audit;

import com.agentflow.common.trace.TraceIdContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 审计消息（→ RocketMQ af-audit-event → audit-service 落库）。 */
public record AuditMessage(String id, String tenantId, String traceId, String executionId,
                           String actorId, String eventType,
                           Map<String, Object> payloadRedacted, Integer tokenUsed,
                           Integer latencyMs, String occurredAt) {

    public static AuditMessage of(String tenantId, String executionId, String actorId,
                                  String eventType, Map<String, Object> payload) {
        return new AuditMessage(UUID.randomUUID().toString(), tenantId,
                TraceIdContext.currentOrCreate(), executionId, actorId, eventType,
                payload, null, null, Instant.now().toString());
    }
}
