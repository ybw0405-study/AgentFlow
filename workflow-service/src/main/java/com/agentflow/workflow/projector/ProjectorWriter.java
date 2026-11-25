package com.agentflow.workflow.projector;

import com.agentflow.workflow.engine.EngineGatewayClient.EngineEvent;
import com.agentflow.workflow.execution.BudgetLedgerMapper;
import com.agentflow.workflow.execution.BudgetLedgerSeenMapper;
import com.agentflow.workflow.execution.NodeExecutionMapper;
import com.agentflow.workflow.execution.ProjectorCursorMapper;
import com.agentflow.workflow.execution.WorkflowExecutionMapper;
import com.agentflow.workflow.gate.GateApprovers;
import com.agentflow.workflow.gate.GateTaskMapper;
import com.agentflow.workflow.notification.NotificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 事件 → MySQL 执行索引的投影写入（与同事务推进游标，幂等可重放）。 */
@Service
public class ProjectorWriter {

    private static final Logger log = LoggerFactory.getLogger(ProjectorWriter.class);

    private final WorkflowExecutionMapper executions;
    private final NodeExecutionMapper nodes;
    private final ProjectorCursorMapper cursors;
    private final BudgetLedgerMapper budgets;
    private final BudgetLedgerSeenMapper budgetSeen;
    private final GateTaskMapper gateTasks;
    private final NotificationMapper notifications;
    private final GateApprovers approvers;
    private final int monthlyCallLimit;
    private final long monthlyTokenLimit;
    private final ObjectMapper json = new ObjectMapper();

    public ProjectorWriter(WorkflowExecutionMapper executions, NodeExecutionMapper nodes,
                           ProjectorCursorMapper cursors, BudgetLedgerMapper budgets,
                           BudgetLedgerSeenMapper budgetSeen,
                           GateTaskMapper gateTasks, NotificationMapper notifications,
                           GateApprovers approvers,
                           @Value("${agentflow.tenant.monthly-call-limit:10000}") int callLimit,
                           @Value("${agentflow.tenant.monthly-token-limit:50000}")
                           long tokenLimit) {
        this.executions = executions;
        this.nodes = nodes;
        this.cursors = cursors;
        this.budgets = budgets;
        this.budgetSeen = budgetSeen;
        this.gateTasks = gateTasks;
        this.notifications = notifications;
        this.approvers = approvers;
        this.monthlyCallLimit = callLimit;
        this.monthlyTokenLimit = tokenLimit;
    }

    public long currentCursor() {
        Long v = cursors.findLastSeq();
        return v == null ? 0L : v;
    }

    @Transactional
    public long writeBatch(List<EngineEvent> events) {
        long maxSeq = 0L;
        for (EngineEvent e : events) {
            project(e);
            maxSeq = Math.max(maxSeq, e.seq());
        }
        cursors.upsert(maxSeq);
        return maxSeq;
    }

    @SuppressWarnings("unchecked")
    private void project(EngineEvent e) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        switch (e.eventType()) {
            case "ExecutionStarted" -> executions.markRunning(e.executionId(), "RUNNING");
            case "ExecutionCompleted" -> executions.markTerminal(
                    e.executionId(), "SUCCEEDED",
                    asInt(p.get("tokenUsed")), asInt(p.get("stepsUsed")), null);
            case "ExecutionFailed" -> executions.markTerminal(
                    e.executionId(), "FAILED",
                    asInt(p.get("tokenUsed")), asInt(p.get("stepsUsed")),
                    (String) p.get("errorCode"));
            case "ExecutionCancelled" -> {
                executions.markTerminal(e.executionId(), "CANCELLED", 0, 0, null);
                // 挂起中的执行被取消：待办立即失效，否则审批人还能点一个已死的执行
                gateTasks.cancelByExecution(e.executionId());
            }
            case "ExecutionTimedOut" -> executions.markTerminal(
                    e.executionId(), "TIMED_OUT", 0, 0, "EXEC-DEADLINE_EXCEEDED");
            case "NodeScheduled" -> upsertNode(e, "SCHEDULED", p, null, null, null);
            case "NodeStarted" -> upsertNode(e, "RUNNING", p, null, null, null);
            case "NodeCompleted" -> {
                upsertNode(e, "SUCCEEDED", p, (String) p.get("outputDigest"), null, null);
                // 成本中心：节点完成的 token 消耗累加到租户月度账本。
                // 事件 seq 唯一键保证多副本/重放时只累加一次；
                // Redis 租约 fail-open 时也不再重复计费。
                if (budgetSeen.claim(e.seq(), e.executionId()) == 1) {
                    budgets.accrue(e.executionId(), BudgetLedgerMapper.currentPeriod(),
                            asInt(p.get("tokenUsed")), monthlyCallLimit, monthlyTokenLimit);
                }
            }
            case "NodeFailed" -> upsertNode(e, "FAILED", p, null,
                    (String) p.get("errorCode"), (String) p.get("message"));
            case "NodeSkipped" -> upsertNode(e, "SKIPPED", p, null, null, null);
            case "NodeSuspended" -> upsertNode(e, "SUSPENDED", p, null, null, null);
            case "GateSuspended" -> {
                // 节点状态只认 NodeSuspended：GateSuspended 的 payload 是「业务载荷」
                // （审批人/提示词/超时），不带 attempt，据此 upsert 会写出一条
                // attempt=0 的幽灵行，把真正的那次尝试留在 RUNNING 上。
                suspendGate(e, p);
            }
            case "GateResolved" -> resolveGate(e, p);
            default -> {
                // Command* / CheckpointCreated / ExecutionCreated / NodeRetryScheduled：
                // 不进节点索引；重试以新 attempt 行呈现（唯一键含 attempt）
            }
        }
    }

    /** GateSuspended → 执行置 SUSPENDED + 待办 + 通知（审批人快照冻结在此刻）。 */
    @SuppressWarnings("unchecked")
    private void suspendGate(EngineEvent e, Map<String, Object> p) {
        executions.markSuspended(e.executionId());
        var brief = executions.findBrief(e.executionId());
        if (brief == null) {
            log.warn("gate suspended for unknown execution {}; skip projection", e.executionId());
            return;
        }
        Map<String, Object> rule = p.get("approversRule") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        List<String> gateApprovers = approvers.resolve(brief.tenantId(), rule);
        String prompt = p.get("prompt") == null ? "" : String.valueOf(p.get("prompt"));
        gateTasks.insert(new GateTaskMapper.GateRow(
                0L, UUID.randomUUID().toString(), e.executionId(), e.nodeKey(),
                brief.tenantId(), brief.definitionId(), prompt,
                writeJson(gateApprovers), writeJson(rule), "PENDING", null, null, null,
                timestampOf(p.get("timeoutAt")), (String) p.get("defaultBranch"), null));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", e.executionId());
        payload.put("nodeKey", e.nodeKey());
        payload.put("prompt", prompt);
        payload.put("definitionId", brief.definitionId());
        String payloadJson = writeJson(payload);
        for (String userId : gateApprovers) {
            notifications.insert(new NotificationMapper.NotificationRow(
                    0L, notificationId(e.executionId(), e.nodeKey(), userId),
                    brief.tenantId(), userId, "GATE_PENDING", payloadJson, null, null));
        }
    }

    /** GateResolved → 待办收敛 + 执行回到 RUNNING（引擎会立刻续跑）。 */
    private void resolveGate(EngineEvent e, Map<String, Object> p) {
        String decision = String.valueOf(p.getOrDefault("decision", ""));
        String status = switch (decision) {
            case "APPROVED" -> "APPROVED";
            case "TIMEOUT" -> "TIMEOUT";
            default -> "REJECTED";
        };
        gateTasks.resolve(e.executionId(), e.nodeKey(), status, decision,
                (String) p.get("resolvedBy"), (String) p.get("comment"));
        executions.markResumed(e.executionId());
    }

    private void upsertNode(EngineEvent e, String status, Map<String, Object> payload,
                            String outputDigest, String errorCode, String errorMessage) {
        nodes.upsert(new NodeExecutionMapper.NodeRow(
                0L, UUID.randomUUID().toString(), e.executionId(),
                e.nodeKey(), e.aggregateVersion(), status,
                asIntOr(payload.get("attempt"), 0),
                (String) payload.get("inputDigest"), outputDigest,
                asInt(payload.get("tokenUsed")), (Integer) payload.get("latencyMs"),
                modelOf(payload),
                0, errorCode, errorMessage,
                "RUNNING".equals(status) ? new Timestamp(System.currentTimeMillis()) : null,
                terminal(status) ? new Timestamp(System.currentTimeMillis()) : null));
    }

    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status)
                || "SKIPPED".equals(status) || "CANCELLED".equals(status);
    }

    /**
     * 节点真正用的模型：LLM 节点把 provider 解析后的模型名放在 output 里
     * （占位名会被回落到服务端默认模型，所以要记回落后的那个）。
     *
     * <p>非 LLM 节点没有这个字段；输出超过 256KB 时 payload 只剩引用与摘要（D5），
     * 同样取不到——这两种情况都返回 null，用量页归到「未标注」，不猜。
     */
    private static String modelOf(Map<String, Object> payload) {
        if (!(payload.get("output") instanceof Map<?, ?> output)) {
            return null;
        }
        Object model = output.get("model");
        if (!(model instanceof String name) || name.isBlank()) {
            return null;
        }
        return name.length() > 128 ? name.substring(0, 128) : name;
    }

    /** 通知 id 由业务键派生（确定性 UUID）：投影重放不会重复通知同一个人。 */
    private static String notificationId(String executionId, String nodeKey, String userId) {
        String key = "gate|" + executionId + "|" + nodeKey + "|" + userId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize gate payload", e);
        }
    }

    private static Timestamp timestampOf(Object iso) {
        if (!(iso instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(text).toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    private static int asInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static int asIntOr(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }
}
