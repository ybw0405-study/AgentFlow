package com.agentflow.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.workflow.audit.AuditMessage;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.engine.EngineGatewayClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 看护的判据只有一条：引擎怎么说。测试围绕「引擎的四种回答」展开——已终态、还在跑、
 * 已挂起、不认识——再加一条「引擎连不上」的连续确认语义。
 */
class ExecutionReaperTest {

    private WorkflowExecutionMapper executions;
    private EngineGatewayClient engine;
    private AuditPublisher audit;
    private ValueOperations<String, String> ops;
    private StringRedisTemplate redis;
    private Map<String, Long> counters;
    private ExecutionReaper reaper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executions = Mockito.mock(WorkflowExecutionMapper.class);
        engine = Mockito.mock(EngineGatewayClient.class);
        audit = Mockito.mock(AuditPublisher.class);
        redis = Mockito.mock(StringRedisTemplate.class);
        ops = Mockito.mock(ValueOperations.class);
        counters = new HashMap<>();
        Mockito.doReturn(ops).when(redis).opsForValue();
        when(ops.increment(anyString())).thenAnswer(invocation ->
                counters.merge(invocation.getArgument(0), 1L, Long::sum));
        Mockito.doAnswer(invocation -> {
            counters.remove(invocation.getArgument(0));
            return true;
        }).when(redis).delete(anyString());
        when(ops.get("af:reap:leader")).thenReturn(null);
        when(ops.setIfAbsent(eq("af:reap:leader"), anyString(), any(Duration.class)))
                .thenReturn(true);
        reaper = new ExecutionReaper(executions, engine, audit, redis, true,
                120_000, 180_000, 50, 3);
    }

    @Test
    void reconcilesWhenEngineAlreadyFinished() {
        givenCandidate(running("e-1"));
        when(engine.findExecutionState("e-1")).thenReturn(Optional.of(Map.of(
                "status", "SUCCEEDED", "tokenUsed", 120, "stepsUsed", 3)));

        var report = reaper.sweep();

        // 事件没投递到，但引擎有终态：把事实抄过来，而不是把它当成死执行收口
        verify(executions).markTerminal(eq("e-1"), eq("SUCCEEDED"), eq(120), eq(3), isNull());
        assertEquals(1, report.reconciled());
        assertEquals(0, report.reaped());
        assertEquals("EXECUTION_RECONCILED", lastAuditType());
    }

    @Test
    void reapsOnlyAfterConsecutiveUnreachableConfirmations() {
        givenCandidate(running("e-2"));
        when(engine.findExecutionState("e-2"))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_001, "down"));

        // 一次 502 / GC 停顿不该把还在跑的执行写成超时
        var first = reaper.sweep();
        var second = reaper.sweep();
        assertEquals(0, first.reaped() + second.reaped());
        verify(executions, never()).markTerminal(any(), any(), anyInt(), anyInt(), any());

        var third = reaper.sweep();
        verify(executions).markTerminal(eq("e-2"), eq("TIMED_OUT"), anyInt(), anyInt(),
                eq(ExecutionReaper.DEADLINE_EXCEEDED));
        assertEquals(1, third.reaped());
        assertEquals("EXECUTION_REAPED", lastAuditType());
    }

    @Test
    void aSuccessfulProbeResetsTheUnreachableStreak() {
        givenCandidate(running("e-2"));
        when(engine.findExecutionState("e-2"))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_001, "down"))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_001, "down"))
                .thenReturn(Optional.of(Map.of("status", "RUNNING")))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_001, "down"))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_001, "down"));

        reaper.sweep(); // 1
        reaper.sweep(); // 2
        reaper.sweep(); // 引擎恢复：计数清零，本轮不动手
        reaper.sweep(); // 重新从 1 开始
        var last = reaper.sweep(); // 2，仍不该收口

        assertEquals(0, last.reaped());
        verify(executions, never()).markTerminal(any(), any(), anyInt(), anyInt(), any());
        // 恢复之后重新从 1 计数，不能带着旧的两轮继续累加
        assertEquals(2, counters.getOrDefault("af:reap:unreachable:e-2", 0L).intValue());
    }

    @Test
    void defersWhileEngineStillHoldsTheLease() {
        givenCandidate(running("e-2"));
        when(engine.findExecutionState("e-2"))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_001, "down"));
        when(redis.hasKey("af:lease:e-2")).thenReturn(true);

        var report = reaper.sweep();

        // 引擎侧还有活跃租约：只暂缓，不收口、也不累计不可达轮数
        assertEquals(0, report.reaped());
        verify(executions, never()).markTerminal(any(), any(), anyInt(), anyInt(), any());
        assertEquals(0L, counters.size());
    }

    @Test
    void reapsDispatchLostForPendingUnknownToEngine() {
        givenCandidate(pending("e-3"));
        when(engine.findExecutionState("e-3")).thenReturn(Optional.empty());

        var report = reaper.sweep();

        // 行插入了、引擎从没收到：这不是「超时」，是派发在两步之间断了；404 立即收口
        verify(executions).markTerminal(eq("e-3"), eq("FAILED"), anyInt(), anyInt(),
                eq(ExecutionReaper.DISPATCH_LOST));
        assertEquals(1, report.reaped());
    }

    @Test
    void leavesExecutionAloneWhenEngineSaysItIsStillRunning() {
        givenCandidate(running("e-4"));
        when(engine.findExecutionState("e-4")).thenReturn(Optional.of(Map.of("status", "RUNNING")));

        var report = reaper.sweep();

        // 引擎说还在跑就什么都不做：超期由引擎自己收口，控制面只兜底「无人收口」
        verify(executions, never()).markTerminal(any(), any(), anyInt(), anyInt(), any());
        assertEquals(1, report.alive());
        assertEquals(0, report.reconciled() + report.reaped());
    }

    @Test
    void promotesPendingToRunningWhenEngineAlreadyStarted() {
        givenCandidate(pending("e-5"));
        when(engine.findExecutionState("e-5")).thenReturn(Optional.of(Map.of("status", "RUNNING")));

        reaper.sweep();

        verify(executions).markRunning("e-5", "RUNNING");
        verify(executions, never()).markTerminal(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void suspendsDriftedRunningExecution() {
        givenCandidate(running("e-6"));
        when(engine.findExecutionState("e-6"))
                .thenReturn(Optional.of(Map.of("status", "SUSPENDED")));

        reaper.sweep();

        // 挂起必然由运行而来：投影停在 RUNNING 时直接挂起是合法的
        verify(executions).markSuspended("e-6");
        verify(executions, never()).markRunning(any(), any());
    }

    @Test
    void oneBadCandidateDoesNotStopTheRound() {
        givenCandidate(running("e-7"), running("e-8"));
        when(engine.findExecutionState("e-7")).thenThrow(new IllegalStateException("boom"));
        when(engine.findExecutionState("e-8"))
                .thenReturn(Optional.of(Map.of("status", "FAILED", "errorCode", "EXEC-MAX_STEPS_EXCEEDED")));

        var report = reaper.sweep();

        verify(executions).markTerminal(eq("e-8"), eq("FAILED"), anyInt(), anyInt(),
                eq("EXEC-MAX_STEPS_EXCEEDED"));
        assertEquals(1, report.reconciled());
        assertEquals(1, report.failed());
    }

    @Test
    void unknownEngineStatusLeavesProjectionUntouched() {
        givenCandidate(running("e-9"));
        when(engine.findExecutionState("e-9"))
                .thenReturn(Optional.of(Map.of("status", "QUARANTINED")));

        var report = reaper.sweep();

        // 不认识的引擎状态：宁可多跑一轮，也不要按猜测写终态
        verify(executions, never()).markTerminal(any(), any(), anyInt(), anyInt(), any());
        assertEquals(1, report.alive());
    }

    @Test
    void skipsRoundWhenAnotherReplicaHoldsTheLease() {
        when(ops.get("af:reap:leader")).thenReturn("other-replica");
        when(ops.setIfAbsent(eq("af:reap:leader"), anyString(), any(Duration.class)))
                .thenReturn(false);

        reaper.poll();

        verify(executions, never()).findStale(any(), any(), anyInt());
    }

    @Test
    void doesNothingWhenDisabled() {
        var off = new ExecutionReaper(executions, engine, audit, redis, false,
                120_000, 180_000, 50, 3);

        off.poll();

        verify(executions, never()).findStale(any(), any(), anyInt());
    }

    private void givenCandidate(WorkflowExecutionMapper.ExecutionRow... rows) {
        when(executions.findStale(any(), any(), anyInt())).thenReturn(List.of(rows));
    }

    private String lastAuditType() {
        var captor = ArgumentCaptor.forClass(AuditMessage.class);
        verify(audit, Mockito.atLeastOnce()).publish(captor.capture());
        var all = captor.getAllValues();
        return all.get(all.size() - 1).eventType();
    }

    private static WorkflowExecutionMapper.ExecutionRow running(String id) {
        return row(id, "RUNNING", new Timestamp(System.currentTimeMillis() - 600_000));
    }

    private static WorkflowExecutionMapper.ExecutionRow pending(String id) {
        return row(id, "PENDING", new Timestamp(System.currentTimeMillis() - 600_000));
    }

    private static WorkflowExecutionMapper.ExecutionRow row(String id, String status,
                                                            Timestamp createdAt) {
        return new WorkflowExecutionMapper.ExecutionRow(1L, id, "d-1", "v-1", "t-1", "u-1",
                status, "ikey-" + id, "trace", 50_000, 50,
                new Timestamp(System.currentTimeMillis() - 300_000), 0, 0, null, null, null,
                createdAt);
    }
}
