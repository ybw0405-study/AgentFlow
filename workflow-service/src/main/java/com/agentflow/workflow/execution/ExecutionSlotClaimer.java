package com.agentflow.workflow.execution;

import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 执行派发的并发上限领取器。
 *
 * <p>把「锁定义行 + 重查幂等 + 数活跃 + 插入」收进一个短事务：先对 workflow_definition
 * 行加 FOR UPDATE 锁，同一 definition 的并发派发因此在锁上串行——后到者拿到锁时前一
 * 事务已经提交，countActive 数到的是包含前一条 PENDING 的最新值，据此决定是否超限。
 * 这样 count 与 insert 之间不再有 check-then-act 窗口。
 *
 * <p>单独成 Bean 而非 {@code ExecutionService} 内部方法：Spring 的 {@code @Transactional}
 * 走代理，同类自调用会绕过事务（同 {@code OutboxClaimer} 的处理）。派发给引擎的网络
 * 调用仍留在事务外，避免长事务。
 */
@Component
public class ExecutionSlotClaimer {

    private final WorkflowDefinitionMapper definitions;
    private final WorkflowExecutionMapper executions;

    public ExecutionSlotClaimer(WorkflowDefinitionMapper definitions,
                                WorkflowExecutionMapper executions) {
        this.definitions = definitions;
        this.executions = executions;
    }

    /** 领取结果：INSERTED=新占坑成功；REPLAY=幂等命中；LIMIT_REACHED=并发已满。 */
    public enum Outcome {
        INSERTED, REPLAY, LIMIT_REACHED
    }

    public record ClaimResult(Outcome outcome, WorkflowExecutionMapper.ExecutionRow row) {
        static ClaimResult inserted() {
            return new ClaimResult(Outcome.INSERTED, null);
        }

        static ClaimResult replay(WorkflowExecutionMapper.ExecutionRow row) {
            return new ClaimResult(Outcome.REPLAY, row);
        }

        static ClaimResult limitReached() {
            return new ClaimResult(Outcome.LIMIT_REACHED, null);
        }
    }

    /**
     * 短事务：串行化地判定并发上限并落一行 PENDING。
     *
     * @param candidate 待插入的执行行（id 已由调用方生成）
     * @param maxConcurrent 并发上限，0 = 不限
     */
    @Transactional
    public ClaimResult claim(WorkflowExecutionMapper.ExecutionRow candidate, int maxConcurrent) {
        // 1. 锁定义行：同一工作流的派发在此排队，锁持有到本方法提交。
        definitions.lockForUpdate(candidate.definitionId());

        // 2. 锁内重查幂等：等锁期间同键请求可能已插入并提交，必须让位给原结果。
        var replayed = executions.findByIdempotency(
                candidate.tenantId(), candidate.idempotencyKey());
        if (replayed != null) {
            return ClaimResult.replay(replayed);
        }

        // 3. 锁内 count：此刻同一 definition 的其它派发都在锁外等待，数字稳定。
        if (maxConcurrent > 0
                && executions.countActive(candidate.definitionId()) >= maxConcurrent) {
            return ClaimResult.limitReached();
        }

        // 4. 插入；唯一键 uq_we_tenant_idem 是并发同键的最后兜底。
        try {
            executions.insert(candidate);
        } catch (DuplicateKeyException e) {
            var existing = executions.findByIdempotency(
                    candidate.tenantId(), candidate.idempotencyKey());
            return ClaimResult.replay(existing);
        }
        return ClaimResult.inserted();
    }
}
