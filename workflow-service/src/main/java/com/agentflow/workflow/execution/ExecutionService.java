package com.agentflow.workflow.execution;

import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.pii.PiiMasker;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.workflow.audit.AuditMessage;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import com.agentflow.workflow.workflow.WorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 执行触发：RBAC + 幂等键 + 政策钳制 + PII 第一层脱敏 + 引擎派发。 */
@Service
public class ExecutionService {

    private final WorkflowExecutionMapper executions;
    private final WorkflowDefinitionMapper definitions;
    private final WorkflowVersionMapper versions;
    private final EngineGatewayClient engine;
    private final AuditPublisher audit;
    private final CanaryService canary;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int defaultTokenBudget;
    private final int defaultMaxSteps;
    private final int defaultDeadlineMs;
    private final List<String> authorizedConnectors;

    public ExecutionService(
            WorkflowExecutionMapper executions, WorkflowDefinitionMapper definitions,
            WorkflowVersionMapper versions, EngineGatewayClient engine, AuditPublisher audit,
            CanaryService canary,
            @Value("${agentflow.tenant.default-token-budget}") int defaultTokenBudget,
            @Value("${agentflow.tenant.default-max-steps}") int defaultMaxSteps,
            @Value("${agentflow.tenant.default-deadline-ms}") int defaultDeadlineMs,
            @Value("${agentflow.engine.authorized-connectors}") String authorizedConnectors) {
        this.executions = executions;
        this.definitions = definitions;
        this.versions = versions;
        this.engine = engine;
        this.audit = audit;
        this.canary = canary;
        this.defaultTokenBudget = defaultTokenBudget;
        this.defaultMaxSteps = defaultMaxSteps;
        this.defaultDeadlineMs = defaultDeadlineMs;
        this.authorizedConnectors = java.util.Arrays.stream(authorizedConnectors.split(","))
                .map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    public record PolicyRequest(Integer tokenBudget, Integer maxSteps, Integer deadlineMs) {
    }

    public record TriggerResult(String executionId, String status, boolean idempotentReplay) {
    }

    public TriggerResult trigger(TenantInfo tenant, String definitionId, String versionId,
                                 String idempotencyKey, Map<String, Object> variables,
                                 PolicyRequest policy, String traceId) {
        if (!tenant.atLeast("EDITOR")) {
            audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                    "WF_FORBIDDEN_ATTEMPT", Map.of("action", "EXECUTE",
                            "definitionId", definitionId)));
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
        var def = definitions.findByIdAndTenant(definitionId, tenant.tenantId());
        if (def == null) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
        var replayed = executions.findByIdempotency(tenant.tenantId(), idempotencyKey);
        if (replayed != null) {
            return new TriggerResult(replayed.id(), replayed.status(), true);
        }
        // 灰度分流（P1）：未显式指定版本时，按 canary 配置路由到新旧版本；审计留痕。
        String effectiveVersionId = versionId;
        if (versionId == null) {
            CanaryService.Decision decision = canary.route(definitionId, def.currentVersionId());
            if (decision != null) {
                effectiveVersionId = decision.versionId();
                audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                        "WF_CANARY_ROUTED", Map.of("definitionId", definitionId,
                                "versionId", decision.versionId(),
                                "canary", decision.canary(), "percent", decision.percent())));
            }
        }
        var version = resolveVersion(def, effectiveVersionId);

        // 政策钳制：任何字段超租户上限即下调并审计（§7）
        int tokenBudget = clamp(policy != null ? policy.tokenBudget() : null, defaultTokenBudget);
        int maxSteps = clamp(policy != null ? policy.maxSteps() : null, defaultMaxSteps);
        int deadlineMs = clamp(policy != null ? policy.deadlineMs() : null, defaultDeadlineMs);
        boolean clamped = (policy != null)
                && ((policy.tokenBudget() != null && policy.tokenBudget() > tokenBudget)
                || (policy.maxSteps() != null && policy.maxSteps() > maxSteps)
                || (policy.deadlineMs() != null && policy.deadlineMs() > deadlineMs));
        if (clamped) {
            audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                    "WF_POLICY_CLAMPED", Map.of("definitionId", definitionId,
                            "requested", policy.toString(),
                            "effective", "tokenBudget=" + tokenBudget + ",maxSteps=" + maxSteps
                                    + ",deadlineMs=" + deadlineMs)));
        }

        // PII 第一层脱敏（pii-v2）：递归处理变量中的字符串
        var masked = maskVariables(variables == null ? Map.of() : variables);
        Map<String, Object> maskedVars = masked.values();
        if (!masked.categories().isEmpty()) {
            audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                    "PII_MASKED", Map.of("definitionId", definitionId,
                            "categories", masked.categories(), "version", PiiMasker.VERSION)));
        }

        String executionId = UUID.randomUUID().toString();
        var row = new WorkflowExecutionMapper.ExecutionRow(
                0L, executionId, definitionId, version.id(), tenant.tenantId(),
                tenant.userId(), "PENDING", idempotencyKey, traceId,
                tokenBudget, maxSteps,
                new Timestamp(System.currentTimeMillis() + deadlineMs),
                0, 0, null, null, null, null);
        try {
            executions.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一约束兜底，返回已存在的执行（US-02 并发触发）
            var existing = executions.findByIdempotency(tenant.tenantId(), idempotencyKey);
            return new TriggerResult(existing.id(), existing.status(), true);
        }

        Map<String, Object> engineRequest = new LinkedHashMap<>();
        engineRequest.put("executionId", executionId);
        engineRequest.put("definitionId", definitionId);
        engineRequest.put("versionId", version.id());
        engineRequest.put("tenantId", tenant.tenantId());
        engineRequest.put("spaceId", tenant.tenantId());
        engineRequest.put("triggeredBy", tenant.userId());
        engineRequest.put("idempotencyKey", idempotencyKey);
        engineRequest.put("versionSnapshot", readJson(version.dslJson()));
        // 内置 mock + 该空间可见的真实连接器（含祖先租户共享，P2）
        List<String> authorized = new ArrayList<>(authorizedConnectors);
        List<String> discovered = engine.connectorIds(tenant.tenantId());
        if (discovered != null) {
            for (String id : discovered) {
                if (!authorized.contains(id)) {
                    authorized.add(id);
                }
            }
        }
        engineRequest.put("authorizedConnectorIds", authorized);
        engineRequest.put("initialVariables", maskedVars);
        engineRequest.put("policy", Map.of("tokenBudget", tokenBudget,
                "maxSteps", maxSteps, "deadlineMs", deadlineMs));
        engineRequest.put("traceId", traceId);
        try {
            engine.submitExecution(engineRequest);
        } catch (BusinessException e) {
            executions.markFailedDispatch(executionId, "FAILED", ErrorCode.AGT_UPSTREAM_001.code());
            throw e;
        }
        audit.publish(AuditMessage.of(tenant.tenantId(), executionId, tenant.userId(),
                "EXECUTION_TRIGGERED", Map.of("definitionId", definitionId,
                        "versionId", version.id(), "clamped", clamped)));
        return new TriggerResult(executionId, "PENDING", false);
    }

    private WorkflowVersionMapper.VersionRow resolveVersion(
            WorkflowDefinitionMapper.DefinitionRow def, String versionId) {
        if (versionId != null) {
            var v = versions.findById(versionId);
            if (v == null || !v.definitionId().equals(def.id())) {
                throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
            }
            return v;
        }
        if (def.currentVersionId() == null) {
            throw new BusinessException(ErrorCode.WF_STATE_001, "no published version");
        }
        return versions.findById(def.currentVersionId());
    }

    private static int clamp(Integer requested, int limit) {
        if (requested == null) {
            return limit;
        }
        return Math.min(Math.max(requested, 1), limit);
    }

    private record MaskedVariables(Map<String, Object> values, List<String> categories) {
    }

    private MaskedVariables maskVariables(Map<String, Object> variables) {
        var categories = new TreeSet<String>();
        Map<String, Object> masked = walk(variables, categories);
        return new MaskedVariables(masked, List.copyOf(categories));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> walk(Map<String, Object> map, java.util.Set<String> categories) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            out.put(e.getKey(), walkValue(e.getValue(), categories));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object walkValue(Object value, java.util.Set<String> categories) {
        if (value instanceof String s) {
            var r = PiiMasker.mask(s);
            categories.addAll(r.categories());
            return r.masked();
        }
        if (value instanceof Map<?, ?> m) {
            return walk((Map<String, Object>) m, categories);
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(walkValue(item, categories));
            }
            return out;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
