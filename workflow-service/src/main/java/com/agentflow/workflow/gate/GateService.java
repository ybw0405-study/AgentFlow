package com.agentflow.workflow.gate;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.workflow.audit.AuditMessage;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.workflow.execution.WorkflowExecutionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审批裁决：RBAC + 审批人校验 + 引擎回调 + outbox 兜底。
 *
 * <p>失败语义：
 * <ul>
 *   <li>执行/待办不存在或跨租户 → 404（不泄露存在性）</li>
 *   <li>非审批人且非本空间 ADMIN → 403</li>
 *   <li>引擎明确拒绝（gate 已裁决）→ 409 WF-STATE-001</li>
 *   <li>引擎不可达 → 落 outbox，返回 DEFERRED（用户动作不丢）</li>
 * </ul>
 */
@Service
public class GateService {

    private static final Logger log = LoggerFactory.getLogger(GateService.class);
    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED");

    private final GateTaskMapper gates;
    private final WorkflowExecutionMapper executions;
    private final GateResolveOutboxMapper outbox;
    private final EngineGatewayClient engine;
    private final AuditPublisher audit;
    private final ObjectMapper json = new ObjectMapper();

    public GateService(GateTaskMapper gates, WorkflowExecutionMapper executions,
                       GateResolveOutboxMapper outbox, EngineGatewayClient engine,
                       AuditPublisher audit) {
        this.gates = gates;
        this.executions = executions;
        this.outbox = outbox;
        this.engine = engine;
        this.audit = audit;
    }

    public List<Map<String, Object>> inbox(TenantInfo tenant, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), 100);
        return gates.inbox(tenant.tenantId(), tenant.userId(), pageSize).stream()
                .map(row -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", row.id());
                    view.put("executionId", row.executionId());
                    view.put("nodeKey", row.nodeKey());
                    view.put("prompt", row.prompt());
                    view.put("approvers", parseApprovers(row.approversJson()));
                    view.put("status", row.status());
                    view.put("timeoutAt", row.timeoutAt());
                    view.put("defaultBranch", row.defaultBranch());
                    view.put("createdAt", row.createdAt());
                    view.put("executionStatus", row.executionStatus());
                    view.put("definitionId", row.definitionId());
                    view.put("triggeredBy", row.triggeredBy());
                    return view;
                })
                .toList();
    }

    public List<GateTaskMapper.GateRow> byExecution(TenantInfo tenant, String executionId) {
        requireExecution(tenant, executionId);
        return gates.listByExecution(executionId);
    }

    public Map<String, Object> resolve(TenantInfo tenant, String executionId, String nodeKey,
                                       String decision, String comment) {
        requireExecution(tenant, executionId);
        var gate = gates.findOne(executionId, nodeKey);
        if (gate == null) {
            throw notFound();
        }
        if (!tenant.atLeast("EDITOR")) {
            audit.publish(AuditMessage.of(tenant.tenantId(), executionId, tenant.userId(),
                    "GATE_FORBIDDEN_ATTEMPT", Map.of("nodeKey", nodeKey)));
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
        List<String> approvers = parseApprovers(gate.approversJson());
        if (!approvers.contains(tenant.userId()) && !tenant.atLeast("ADMIN")) {
            audit.publish(AuditMessage.of(tenant.tenantId(), executionId, tenant.userId(),
                    "GATE_FORBIDDEN_ATTEMPT",
                    Map.of("nodeKey", nodeKey, "reason", "not_an_approver")));
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "not an approver");
        }
        String normalized = decision == null ? "" : decision.toUpperCase(Locale.ROOT);
        if (!DECISIONS.contains(normalized)) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001,
                    "decision must be APPROVED or REJECTED");
        }
        if (!"PENDING".equals(gate.status())) {
            return Map.of("status", "ALREADY_RESOLVED", "deferred", false);
        }

        try {
            var result = engine.resolveGate(executionId, nodeKey, normalized, tenant.userId(),
                    comment);
            audit.publish(AuditMessage.of(tenant.tenantId(), executionId, tenant.userId(),
                    "GATE_RESOLVED", Map.of("nodeKey", nodeKey, "decision", normalized,
                            "branch", String.valueOf(result.getOrDefault("branch", "")))));
            return Map.of(
                    "status", "RESOLVED",
                    "branch", String.valueOf(result.getOrDefault("branch", "")),
                    "deferred", false);
        } catch (BusinessException e) {
            if (e.errorCode() != ErrorCode.AGT_UPSTREAM_001) {
                throw e;
            }
            enqueueResolve(gate, tenant, normalized, comment, e.getMessage());
            return Map.of("status", "DEFERRED", "deferred", true);
        }
    }

    private void enqueueResolve(GateTaskMapper.GateRow gate, TenantInfo tenant,
                                String decision, String comment, String cause) {
        outbox.enqueue(new GateResolveOutboxMapper.OutboxRow(
                UUID.randomUUID().toString(), gate.executionId(), gate.nodeKey(), decision,
                tenant.userId(), comment, "PENDING", 0, null, null));
        audit.publish(AuditMessage.of(tenant.tenantId(), gate.executionId(), tenant.userId(),
                "GATE_RESOLVE_DEFERRED", Map.of("nodeKey", gate.nodeKey(),
                        "decision", decision, "cause", String.valueOf(cause))));
        log.warn("engine unreachable, gate resolve deferred execution={} node={}",
                gate.executionId(), gate.nodeKey());
    }

    private void requireExecution(TenantInfo tenant, String executionId) {
        var brief = executions.findBrief(executionId);
        if (brief == null || !tenant.tenantId().equals(brief.tenantId())) {
            throw notFound();
        }
    }

    private List<String> parseApprovers(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
    }
}
