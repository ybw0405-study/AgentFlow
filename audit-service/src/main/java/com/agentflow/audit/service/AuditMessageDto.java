package com.agentflow.audit.service;

import java.util.Map;

/** MQ 上的审计消息（与 workflow-service 的 AuditMessage 同构）。 */
public record AuditMessageDto(String id, String tenantId, String traceId, String executionId,
                              String actorId, String eventType,
                              Map<String, Object> payloadRedacted, Integer tokenUsed,
                              Integer latencyMs, String occurredAt) {
}
