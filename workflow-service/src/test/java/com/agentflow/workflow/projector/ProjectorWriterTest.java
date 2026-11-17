package com.agentflow.workflow.projector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.workflow.engine.EngineGatewayClient.EngineEvent;
import com.agentflow.workflow.execution.BudgetLedgerMapper;
import com.agentflow.workflow.execution.NodeExecutionMapper;
import com.agentflow.workflow.execution.ProjectorCursorMapper;
import com.agentflow.workflow.execution.WorkflowExecutionMapper;
import com.agentflow.workflow.gate.GateApprovers;
import com.agentflow.workflow.gate.GateTaskMapper;
import com.agentflow.workflow.notification.NotificationMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 投影器：成本账本累加 + human_gate 待办/通知投影。 */
class ProjectorWriterTest {

    private final WorkflowExecutionMapper executions = mock(WorkflowExecutionMapper.class);
    private final NodeExecutionMapper nodes = mock(NodeExecutionMapper.class);
    private final ProjectorCursorMapper cursors = mock(ProjectorCursorMapper.class);
    private final BudgetLedgerMapper budgets = mock(BudgetLedgerMapper.class);
    private final GateTaskMapper gateTasks = mock(GateTaskMapper.class);
    private final NotificationMapper notifications = mock(NotificationMapper.class);
    private final GateApprovers approvers = mock(GateApprovers.class);
    private final ProjectorWriter writer = new ProjectorWriter(executions, nodes, cursors, budgets,
            gateTasks, notifications, approvers, 10_000, 50_000L);

    private static EngineEvent event(long seq, String type, Map<String, Object> payload) {
        return new EngineEvent(seq, "exec-1", type, "draft", payload, (int) seq, "now");
    }

    private static EngineEvent gateEvent(long seq, String type, Map<String, Object> payload) {
        return new EngineEvent(seq, "exec-1", type, "gate", payload, (int) seq, "now");
    }

    @Test
    void nodeCompletedAccruesTokenUsage() {
        when(cursors.findLastSeq()).thenReturn(0L);

        writer.writeBatch(List.of(event(1, "NodeCompleted",
                Map.of("tokenUsed", 120, "outputDigest", "abc", "attempt", 1))));

        verify(budgets).accrue("exec-1", 120, 10_000, 50_000L);
    }

    @Test
    void otherEventsDoNotAccrue() {
        when(cursors.findLastSeq()).thenReturn(0L);

        writer.writeBatch(List.of(
                event(1, "NodeStarted", Map.of("attempt", 1)),
                event(2, "CommandCompleted", Map.of("callId", "c"))));

        verify(budgets, never()).accrue(anyString(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void everyCompletedNodeAccruesOnce() {
        when(cursors.findLastSeq()).thenReturn(0L);

        writer.writeBatch(List.of(
                event(1, "NodeCompleted", Map.of("tokenUsed", 10)),
                event(2, "NodeCompleted", Map.of("tokenUsed", 20))));

        verify(budgets).accrue("exec-1", 10, 10_000, 50_000L);
        verify(budgets).accrue("exec-1", 20, 10_000, 50_000L);
        verify(budgets, times(2)).accrue(anyString(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void cursorAdvancesToMaxSeq() {
        when(cursors.findLastSeq()).thenReturn(0L);

        long next = writer.writeBatch(List.of(
                event(5, "NodeStarted", Map.of()),
                event(9, "NodeCompleted", Map.of("tokenUsed", 1))));

        assertEquals(9L, next);
        verify(cursors).upsert(eq(9L));
        verify(nodes, times(2)).upsert(any());   // NodeStarted + NodeCompleted 各一行
    }

    @Test
    void gateSuspendedCreatesTaskAndNotifiesEveryApprover() {
        when(executions.findBrief("exec-1")).thenReturn(
                new WorkflowExecutionMapper.ExecutionBrief("exec-1", "space-a", "def-1", "v1"));
        when(approvers.resolve(eq("space-a"), any())).thenReturn(List.of("u-1", "u-2"));

        writer.writeBatch(List.of(gateEvent(1, "GateSuspended", Map.of(
                "prompt", "确认发布吗？",
                "approversRule", Map.of("type", "ROLE", "role", "ADMIN"),
                "timeoutAt", "2026-11-18T00:00:00Z",
                "defaultBranch", "approved"))));

        verify(executions).markSuspended("exec-1");
        verify(gateTasks).insert(any());
        verify(notifications, times(2)).insert(any());
        // 节点状态由 NodeSuspended 投影；GateSuspended 不带 attempt，不能写节点索引
        verify(nodes, never()).upsert(any());
    }

    @Test
    void nodeSuspendedMarksNodeRowWithItsAttempt() {
        writer.writeBatch(List.of(event(6, "NodeSuspended",
                Map.of("reason", "HUMAN_GATE", "attempt", 2))));

        ArgumentCaptor<NodeExecutionMapper.NodeRow> row =
                ArgumentCaptor.forClass(NodeExecutionMapper.NodeRow.class);
        verify(nodes).upsert(row.capture());
        assertEquals("SUSPENDED", row.getValue().status());
        assertEquals(2, row.getValue().attempt(), "必须落在真正的那次尝试上，不能写 attempt=0");
    }

    @Test
    void gateResolvedMapsDecisionAndResumesExecution() {
        writer.writeBatch(List.of(gateEvent(2, "GateResolved", Map.of(
                "decision", "TIMEOUT", "resolvedBy", "system:timeout"))));

        verify(gateTasks).resolve(eq("exec-1"), eq("gate"), eq("TIMEOUT"), eq("TIMEOUT"),
                eq("system:timeout"), eq(null));
        verify(executions).markResumed("exec-1");
    }

    @Test
    void cancelClosesPendingGates() {
        writer.writeBatch(List.of(event(3, "ExecutionCancelled", Map.of("reason", "user"))));

        verify(gateTasks).cancelByExecution("exec-1");
    }
}
