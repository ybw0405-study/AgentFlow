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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 执行触发：RBAC + 幂等键 + 政策钳制 + PII 第一层脱敏 + 引擎派发。 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final WorkflowExecutionMapper executions;
    private final WorkflowDefinitionMapper definitions;
    private final WorkflowVersionMapper versions;
    private final EngineGatewayClient engine;
    private final AuditPublisher audit;
    private final CanaryService canary;
    private final UsageService usage;
    private final BudgetLedgerMapper budgets;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int defaultTokenBudget;
    private final int defaultMaxSteps;
    private final int defaultDeadlineMs;
    private final List<String> authorizedConnectors;
    /** 试跑允许的最长等待：上游可能是两三次真实模型调用，给足但不无限等。 */
    private static final int PREVIEW_TIMEOUT_MS = 120_000;

    public ExecutionService(
            WorkflowExecutionMapper executions, WorkflowDefinitionMapper definitions,
            WorkflowVersionMapper versions, EngineGatewayClient engine, AuditPublisher audit,
            CanaryService canary, UsageService usage, BudgetLedgerMapper budgets,
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
        this.usage = usage;
        this.budgets = budgets;
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

    /**
     * 这个工作流正在占用引擎的执行数（{@code PENDING} / {@code RUNNING}）。
     *
     * <p>调度器用它判断补跑还能不能继续；把「什么算占用」收在这里一份，
     * 免得触发侧与派发侧各写一套状态集合。
     */
    public int activeCount(String definitionId) {
        return executions.countActive(definitionId);
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
        return dispatch(tenant.tenantId(), tenant.userId(), definitionId, versionId,
                idempotencyKey, variables, policy, traceId);
    }

    /**
     * 外部入口（webhook / 定时）的执行触发：没有登录用户，凭据由调用方（触发器）校验。
     *
     * <p>仍然走同一套派发逻辑：幂等键、版本解析、政策钳制、PII 脱敏、审计一个不少，
     * 区别只是不再做租户角色判断——触发器的权限在创建时已经由 EDITOR 确认过。
     */
    public TriggerResult triggerFromExternal(String tenantId, String actorId, String definitionId,
                                             String versionId, String idempotencyKey,
                                             Map<String, Object> variables, String traceId) {
        return dispatch(tenantId, actorId, definitionId, versionId, idempotencyKey,
                variables, null, traceId);
    }

    private TriggerResult dispatch(String tenantId, String userId, String definitionId,
                                   String versionId, String idempotencyKey,
                                   Map<String, Object> variables, PolicyRequest policy,
                                   String traceId) {
        var def = definitions.findByIdAndTenant(definitionId, tenantId);
        if (def == null) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
        var replayed = executions.findByIdempotency(tenantId, idempotencyKey);
        if (replayed != null) {
            return new TriggerResult(replayed.id(), replayed.status(), true);
        }
        // 并发上限（0 = 不限）：先挡在门口，不要先插一行再失败——那会留下一条假的 PENDING。
        // 幂等回放放在前面：已经跑过的执行不算「新占一个坑」，重放必须能拿到原结果。
        if (def.maxConcurrent() > 0 && executions.countActive(def.id()) >= def.maxConcurrent()) {
            audit.publish(AuditMessage.of(tenantId, null, userId,
                    "EXECUTION_REJECTED", Map.of("definitionId", definitionId,
                            "reason", "CONCURRENCY_LIMIT",
                            "maxConcurrent", def.maxConcurrent())));
            throw new BusinessException(ErrorCode.WF_CONCURRENCY_001,
                    "workflow already has " + def.maxConcurrent() + " running executions");
        }
        // 月度额度硬约束（P1-9）：budget_ledger 不再只是展示用的记账本。
        // 账期口径与 UsageService / 投影器一致：Asia/Shanghai 的 yyyy-MM。
        String period = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        var ledger = budgets.find(tenantId, period).orElse(null);
        if (ledger != null
                && ((ledger.limitCalls() > 0 && ledger.callsUsed() >= ledger.limitCalls())
                || (ledger.limitTokens() > 0 && ledger.tokensUsed() >= ledger.limitTokens()))) {
            audit.publish(AuditMessage.of(tenantId, null, userId, "EXECUTION_REJECTED",
                    Map.of("definitionId", definitionId, "reason", "BUDGET_EXCEEDED",
                            "period", period, "callsUsed", ledger.callsUsed(),
                            "limitCalls", ledger.limitCalls())));
            throw new BusinessException(ErrorCode.WF_BUDGET_001, "monthly budget exhausted");
        }
        // 灰度分流（P1）：未显式指定版本时，按 canary 配置路由到新旧版本；审计留痕。
        String effectiveVersionId = versionId;
        if (versionId == null) {
            CanaryService.Decision decision = canary.route(definitionId, def.currentVersionId());
            if (decision != null) {
                effectiveVersionId = decision.versionId();
                audit.publish(AuditMessage.of(tenantId, null, userId,
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
            audit.publish(AuditMessage.of(tenantId, null, userId,
                    "WF_POLICY_CLAMPED", Map.of("definitionId", definitionId,
                            "requested", policy.toString(),
                            "effective", "tokenBudget=" + tokenBudget + ",maxSteps=" + maxSteps
                                    + ",deadlineMs=" + deadlineMs)));
        }

        // PII 第一层脱敏（pii-v2）：递归处理变量中的字符串
        var masked = maskVariables(variables == null ? Map.of() : variables);
        Map<String, Object> maskedVars = masked.values();
        if (!masked.categories().isEmpty()) {
            audit.publish(AuditMessage.of(tenantId, null, userId,
                    "PII_MASKED", Map.of("definitionId", definitionId,
                            "categories", masked.categories(), "version", PiiMasker.VERSION)));
        }

        String executionId = UUID.randomUUID().toString();
        var row = new WorkflowExecutionMapper.ExecutionRow(
                0L, executionId, definitionId, version.id(), tenantId,
                userId, "PENDING", idempotencyKey, traceId,
                tokenBudget, maxSteps,
                new Timestamp(System.currentTimeMillis() + deadlineMs),
                0, 0, null, null, null, null);
        try {
            executions.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一约束兜底，返回已存在的执行（US-02 并发触发）
            var existing = executions.findByIdempotency(tenantId, idempotencyKey);
            return new TriggerResult(existing.id(), existing.status(), true);
        }

        Map<String, Object> engineRequest = new LinkedHashMap<>();
        engineRequest.put("executionId", executionId);
        engineRequest.put("definitionId", definitionId);
        engineRequest.put("versionId", version.id());
        engineRequest.put("tenantId", tenantId);
        engineRequest.put("spaceId", tenantId);
        engineRequest.put("triggeredBy", userId);
        engineRequest.put("idempotencyKey", idempotencyKey);
        engineRequest.put("versionSnapshot", readJson(version.dslJson()));
        engineRequest.put("authorizedConnectorIds", authorizedFor(tenantId));
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
        audit.publish(AuditMessage.of(tenantId, executionId, userId,
                "EXECUTION_TRIGGERED", Map.of("definitionId", definitionId,
                        "versionId", version.id(), "clamped", clamped)));
        return new TriggerResult(executionId, "PENDING", false);
    }

    /**
     * 单节点试跑：把画布当前草稿 + 目标节点交给引擎，由引擎裁出上游真实跑一遍。
     *
     * <p>不落执行记录、不产生版本：试跑是调试动作，不该污染执行列表，也不该被当成
     * 一次正式运行去审计成 EXECUTION_TRIGGERED。真实模型产生的 token 仍由引擎预算
     * 与事件流记录，审计单列 NODE_PREVIEWED。
     */
    public Map<String, Object> preview(TenantInfo tenant, String definitionId, String nodeKey,
                                       Map<String, Object> dsl, Map<String, Object> variables) {
        if (!tenant.atLeast("EDITOR")) {
            audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                    "WF_FORBIDDEN_ATTEMPT", Map.of("action", "PREVIEW",
                            "definitionId", definitionId)));
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
        var def = definitions.findByIdAndTenant(definitionId, tenant.tenantId());
        if (def == null) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
        Map<String, Object> snapshot = dsl;
        if (snapshot == null && def.draftDsl() != null) {
            snapshot = readJson(def.draftDsl());
        }
        if (snapshot == null && def.currentVersionId() != null) {
            var version = versions.findById(def.currentVersionId());
            if (version != null) {
                snapshot = readJson(version.dslJson());
            }
        }
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.WF_STATE_001, "no draft to preview");
        }

        var masked = maskVariables(variables == null ? Map.of() : variables);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("dsl", snapshot);
        request.put("nodeKey", nodeKey);
        request.put("variables", masked.values());
        request.put("tenantId", tenant.tenantId());
        request.put("spaceId", tenant.tenantId());
        request.put("requestedBy", tenant.userId());
        request.put("authorizedConnectorIds", authorizedFor(tenant.tenantId()));
        request.put("tokenBudget", defaultTokenBudget);
        request.put("timeoutMs", PREVIEW_TIMEOUT_MS);

        Map<String, Object> result = engine.previewNode(request);
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "NODE_PREVIEWED", Map.of("definitionId", definitionId, "nodeKey", nodeKey,
                        "status", String.valueOf(result.getOrDefault("status", "")),
                        "tokenUsed", result.getOrDefault("totalTokens", 0))));
        recordPreviewUsage(tenant, definitionId, nodeKey, snapshot, result);
        return result;
    }

    /**
     * 试跑的消耗进账：明细落 preview_usage，token 与节点数累加进 budget_ledger。
     *
     * <p>记账失败不改变试跑结果（用户要的是节点输出），但会留下一条 warn——账不能悄悄丢。
     */
    private void recordPreviewUsage(TenantInfo tenant, String definitionId, String nodeKey,
                                    Map<String, Object> dsl, Map<String, Object> result) {
        try {
            usage.recordPreview(tenant.tenantId(), new UsageService.PreviewRecord(
                    definitionId, nodeKey, tenant.userId(),
                    String.valueOf(result.getOrDefault("status", "UNKNOWN")),
                    modelOf(dsl, nodeKey),
                    traceSize(result.get("trace")),
                    asInt(result.get("totalTokens")),
                    result.get("latencyMs") instanceof Number n ? n.intValue() : null));
        } catch (Exception e) {
            log.warn("preview usage not recorded for {}:{}: {}", definitionId, nodeKey, e.toString());
        }
    }

    /** 目标节点的模型名：试跑账单要能回答「这次调试花在哪个模型上」。 */
    static String modelOf(Map<String, Object> dsl, String nodeKey) {
        if (dsl == null || !(dsl.get("nodes") instanceof List<?> nodes)) {
            return null;
        }
        for (Object item : nodes) {
            if (!(item instanceof Map<?, ?> node) || !nodeKey.equals(node.get("key"))) {
                continue;
            }
            if (node.get("config") instanceof Map<?, ?> config
                    && config.get("model") instanceof String model && !model.isBlank()) {
                return model;
            }
        }
        return null;
    }

    private static int traceSize(Object trace) {
        return trace instanceof List<?> list ? list.size() : 0;
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    /** 授权清单：后台配置 + 该空间可见的真实连接器（含祖先租户共享）。 */
    private List<String> authorizedFor(String tenantId) {
        List<String> authorized = new ArrayList<>(authorizedConnectors);
        List<String> discovered = engine.connectorIds(tenantId);
        if (discovered != null) {
            for (String id : discovered) {
                if (!authorized.contains(id)) {
                    authorized.add(id);
                }
            }
        }
        return authorized;
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
