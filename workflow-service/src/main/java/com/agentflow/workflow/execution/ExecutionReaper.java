package com.agentflow.workflow.execution;

import com.agentflow.common.error.BusinessException;
import com.agentflow.workflow.audit.AuditMessage;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.workflow.lease.RedisLease;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 执行看护：把「永远停在运行中」的执行收口，并顺手修掉投影漂移。
 *
 * <p>事件的唯一真相在引擎侧，控制面只是投影——这带来一个必然的漏洞：引擎没发出终态事件
 * （进程崩了、事件流被清、派发在「插入行」与「提交引擎」之间断掉）时，投影就永远停在
 * PENDING / RUNNING。列表里那一行会一直显示「运行中」，用户既不知道它死了，也没法把它结束掉。
 *
 * <p>这里做两件事，都用「引擎侧状态」作为判据，而不是凭空猜：
 *
 * <ol>
 *   <li><b>自愈</b>：引擎说这条执行已经终态（或已开始 / 已挂起），而投影还停在旧状态——说明
 *       只是事件没投递到投影器。按引擎的状态把投影补上，审计记 {@code EXECUTION_RECONCILED}。
 *       这时候不制造新事实，只是把已知事实抄过来。</li>
 *   <li><b>收口</b>：引擎不认识这条执行（或者压根连不上），而它早就过了自己的 deadline / 早已
 *       超过派发宽限——不会再有事件来了。落终态并审计 {@code EXECUTION_REAPED}：PENDING 记
 *       {@code EXEC-DISPATCH-LOST}（派发丢了，从未开始），RUNNING 记
 *       {@code EXEC-DEADLINE_EXCEEDED}（超时）。</li>
 * </ol>
 *
 * <p>三个刻意的取舍：
 *
 * <ul>
 *   <li><b>只在过了 deadline（或派发宽限）之后动手</b>。引擎自己会在 deadline 到点时发
 *       {@code ExecutionTimedOut}，控制面再等一段余量，避免和正常收尾抢同一行；</li>
 *   <li><b>候选先查引擎再落终态</b>。少了这一步，「投影器卡了 10 秒」会被误判成「执行死了」，
 *       把一条正在跑的执行写成超时；</li>
 *   <li><b>引擎说还在跑就什么都不做</b>，哪怕它已经超期——超期由引擎负责收口，控制面只兜底
 *       「无人收口」的情况。</li>
 * </ul>
 */
@Component
public class ExecutionReaper {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReaper.class);

    private static final String LEASE_KEY = "af:reap:leader";
    /** 租约覆盖整轮扫描（候选最多 200 条，每条一次引擎调用）。 */
    private static final Duration LEASE_TTL = Duration.ofSeconds(120);
    private static final Set<String> TERMINAL =
            Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT");

    /** 派发丢失：行已插入，引擎从没收到过。 */
    static final String DISPATCH_LOST = "EXEC-DISPATCH-LOST";
    /** 超过 deadline 仍未收口：与引擎侧超时用同一个码，界面不必区分来源。 */
    static final String DEADLINE_EXCEEDED = "EXEC-DEADLINE_EXCEEDED";

    /** 看护者自己的审计身份：不是某个用户做的，别冒充。 */
    static final String ACTOR = "system:reaper";

    private final WorkflowExecutionMapper executions;
    private final EngineGatewayClient engine;
    private final AuditPublisher audit;
    private final RedisLease lease;
    private final boolean enabled;
    private final long graceMs;
    private final long pendingGraceMs;
    private final int batch;

    public ExecutionReaper(WorkflowExecutionMapper executions, EngineGatewayClient engine,
                           AuditPublisher audit, StringRedisTemplate redis,
                           @Value("${agentflow.execution.reap.enabled:true}") boolean enabled,
                           @Value("${agentflow.execution.reap.grace-ms:120000}") long graceMs,
                           @Value("${agentflow.execution.reap.pending-grace-ms:180000}")
                           long pendingGraceMs,
                           @Value("${agentflow.execution.reap.batch:50}") int batch) {
        this.executions = executions;
        this.engine = engine;
        this.audit = audit;
        this.lease = new RedisLease(redis);
        this.enabled = enabled;
        this.graceMs = graceMs;
        this.pendingGraceMs = pendingGraceMs;
        this.batch = batch;
    }

    /** 一轮扫描的结果。数字都进日志与管理端，出问题时能回答「看护到底做了什么」。 */
    public record SweepReport(int scanned, int reconciled, int reaped, int alive, int failed) {

        public String describe() {
            return "扫描 " + scanned + " 条：自愈 " + reconciled + "、收口 " + reaped
                    + "、仍在运行 " + alive + "、异常 " + failed;
        }
    }

    /**
     * 管理端预览用的一行：与看护实际会处理的候选同一套口径（同样的宽限、同样的排序），
     * 只多算了「超期多久」这类给人看的字段。
     */
    public record StaleView(String executionId, String tenantId, String definitionId,
                            String status, String createdAt, String deadlineAt,
                            long overdueSeconds, boolean neverStarted) {
    }

    private enum Outcome { RECONCILED, REAPED, ALIVE }

    @Scheduled(fixedDelayString = "${agentflow.execution.reap.interval-ms:60000}",
            initialDelayString = "${agentflow.execution.reap.initial-delay-ms:45000}")
    public void poll() {
        if (!enabled || !lease.hold(LEASE_KEY, LEASE_TTL)) {
            return;
        }
        try {
            sweep();
        } catch (Exception e) {
            // 看护本身失败不该让进程不安：下一轮再来
            log.warn("execution reap round failed: {}", e.toString());
            log.debug("execution reap round failed", e);
        }
    }

    public SweepReport sweep() {
        Instant now = Instant.now();
        List<WorkflowExecutionMapper.ExecutionRow> candidates = stale(now, batch);
        int reconciled = 0;
        int reaped = 0;
        int alive = 0;
        int failed = 0;
        for (WorkflowExecutionMapper.ExecutionRow row : candidates) {
            try {
                switch (inspect(row)) {
                    case RECONCILED -> reconciled++;
                    case REAPED -> reaped++;
                    default -> alive++;
                }
            } catch (Exception e) {
                // 一条坏行不该让整轮停下：剩下的候选下一轮还在（它们不会自己消失）
                failed++;
                log.warn("reap candidate {} failed: {}", row.id(), e.toString());
                log.debug("reap candidate failed", e);
            }
        }
        if (reconciled + reaped > 0) {
            log.info("execution reap: {}", new SweepReport(candidates.size(), reconciled, reaped,
                    alive, failed).describe());
        }
        return new SweepReport(candidates.size(), reconciled, reaped, alive, failed);
    }

    /** 当前会被看护处理的候选（只读，不改任何状态）。 */
    public List<StaleView> staleViews(int limit) {
        return stale(Instant.now(), limit).stream().map(ExecutionReaper::view).toList();
    }

    private List<WorkflowExecutionMapper.ExecutionRow> stale(Instant now, int limit) {
        return executions.findStale(
                Timestamp.from(now.minusMillis(graceMs)),
                Timestamp.from(now.minusMillis(pendingGraceMs)),
                Math.min(Math.max(limit, 1), 200));
    }

    /** 候选 → 对外视图：超期秒数按「离 deadline 多久」算，PENDING 按创建时间算。 */
    private static StaleView view(WorkflowExecutionMapper.ExecutionRow row) {
        boolean neverStarted = "PENDING".equals(row.status());
        Instant since = neverStarted || row.deadlineAt() == null
                ? (row.createdAt() == null ? Instant.now() : row.createdAt().toInstant())
                : row.deadlineAt().toInstant();
        return new StaleView(row.id(), row.tenantId(), row.definitionId(), row.status(),
                row.createdAt() == null ? null : row.createdAt().toInstant().toString(),
                row.deadlineAt() == null ? null : row.deadlineAt().toInstant().toString(),
                Math.max(0, Duration.between(since, Instant.now()).getSeconds()), neverStarted);
    }

    private Outcome inspect(WorkflowExecutionMapper.ExecutionRow row) {
        final Optional<Map<String, Object>> state;
        try {
            state = engine.findExecutionState(row.id());
        } catch (BusinessException e) {
            // 引擎不可达：候选本来就「早该结束」，按「无人收口」兜底
            return postMortem(row, "engine-unreachable");
        }
        if (state.isEmpty()) {
            return postMortem(row, "engine-unknown");
        }
        return reconcile(row, state.get());
    }

    /** 引擎的状态与投影不一致：把引擎的事实抄到投影上。 */
    private Outcome reconcile(WorkflowExecutionMapper.ExecutionRow row, Map<String, Object> state) {
        String status = text(state.get("status"));
        if (status == null || status.equals(row.status())) {
            return Outcome.ALIVE;
        }
        if (!TERMINAL.contains(status) && !"RUNNING".equals(status) && !"SUSPENDED".equals(status)) {
            // 未知状态：不动投影，等事件。宁可多跑一轮，也不要按猜测写状态
            log.debug("execution {} reports unknown status {}; leave projection", row.id(), status);
            return Outcome.ALIVE;
        }
        apply(row.id(), row.status(), status, asInt(state.get("tokenUsed")),
                asInt(state.get("stepsUsed")), text(state.get("errorCode")));
        audit.publish(AuditMessage.of(row.tenantId(), row.id(), ACTOR, "EXECUTION_RECONCILED",
                Map.of("from", row.status(), "to", status, "cause", "projection-drift")));
        log.info("reconciled execution {} {} → {} from engine state", row.id(), row.status(), status);
        return Outcome.RECONCILED;
    }

    private void apply(String executionId, String from, String to, int tokenUsed, int stepsUsed,
                       String errorCode) {
        switch (to) {
            case "RUNNING" -> executions.markRunning(executionId, "RUNNING");
            case "SUSPENDED" -> {
                // 挂起必然由运行而来：投影还停在 PENDING 时先补上 RUNNING，
                // 否则 markSuspended 的状态守卫会把这行挡掉，状态链条就断在这里
                if ("PENDING".equals(from)) {
                    executions.markRunning(executionId, "RUNNING");
                }
                executions.markSuspended(executionId);
            }
            default -> executions.markTerminal(executionId, to, tokenUsed, stepsUsed, errorCode);
        }
    }

    /** 不会再有事件来了：落终态。PENDING 归因派发丢失，RUNNING 归因超时。 */
    private Outcome postMortem(WorkflowExecutionMapper.ExecutionRow row, String cause) {
        boolean neverStarted = "PENDING".equals(row.status());
        String code = neverStarted ? DISPATCH_LOST : DEADLINE_EXCEEDED;
        String status = neverStarted ? "FAILED" : "TIMED_OUT";
        executions.markTerminal(row.id(), status, row.tokenUsed(), row.stepsUsed(), code);
        audit.publish(AuditMessage.of(row.tenantId(), row.id(), ACTOR, "EXECUTION_REAPED",
                Map.of("from", row.status(), "to", status, "errorCode", code, "cause", cause,
                        "deadlineAt", row.deadlineAt() == null ? ""
                                : row.deadlineAt().toInstant().toString())));
        log.warn("reaped execution {} ({} → {}) cause={} code={}",
                row.id(), row.status(), status, cause, code);
        return Outcome.REAPED;
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
