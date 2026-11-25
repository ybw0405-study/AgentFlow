package com.agentflow.workflow.projector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.agentflow.workflow.execution.BudgetLedgerSeenMapper;
import com.agentflow.workflow.execution.WorkflowExecutionMapper;
import com.agentflow.workflow.gate.GateApprovers;
import com.agentflow.workflow.gate.GateTaskMapper;
import com.agentflow.workflow.notification.NotificationMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 投影器：成本账本累加 + human_gate 待办/通知投影。 */
class ProjectorWriterTest {

    private final WorkflowExecutionMapper executions = mock(WorkflowExecutionMapper.class);
    private final NodeExecutionMapper nodes = mock(NodeExecutionMapper.class);
    private final ProjectorCursorMapper cursors = mock(ProjectorCursorMapper.class);
    private final BudgetLedgerMapper budgets = mock(BudgetLedgerMapper.class);
    private final BudgetLedgerSeenMapper budgetSeen = mock(BudgetLedgerSeenMapper.class);
    private final GateTaskMapper gateTasks = mock(GateTaskMapper.class);
    private final NotificationMapper notifications = mock(NotificationMapper.class);
    private final GateApprovers approvers = mock(GateApprovers.class);
    private final ProjectorWriter writer = new ProjectorWriter(executions, nodes, cursors, budgets,
            budgetSeen, gateTasks, notifications, approvers, 10_000, 50_000L);

    @BeforeEach
    void stubBudgetSeen() {
        // 默认每个事件 seq 首次投影成功占位
        when(budgetSeen.claim(anyLong(), anyString())).thenReturn(1);
    }

    @Test
    void markTerminalSqlGuardsAgainstOverwritingTerminalRows() throws Exception {
        // 终态不可逆必须落在 SQL 层（mock 测不出 WHERE），直接校验注解文本。
        var method = WorkflowExecutionMapper.class.getMethod("markTerminal", String.class,
                String.class, int.class, int.class, String.class);
        Update update = method.getAnnotation(Update.class);
        String sql = String.join(" ", update.value());
        assertTrue(sql.contains("status IN ('PENDING','RUNNING','SUSPENDED')"),
                "markTerminal 必须带前置状态条件，否则乱序事件可把终态改回去");
    }

    @Test
    void budgetAccrualPeriodComesFromJavaNotSqlNow() throws Exception {
        // 账期必须由 Java 统一计算传入，SQL 不得再用 NOW(3)（跨月分裂账本）。
        for (var method : BudgetLedgerMapper.class.getDeclaredMethods()) {
            if (!method.getName().equals("accrue") && !method.getName().equals("accrueByTenant")) {
                continue;
            }
            Update update = method.getAnnotation(Update.class);
            String sql = String.join(" ", update.value());
            assertTrue(sql.contains("#{period}"), method.getName() + " 必须用参数化 period");
            assertTrue(!sql.contains("NOW("), method.getName() + " 不得用 NOW() 算账期");
        }
        assertTrue(BudgetLedgerMapper.currentPeriod().matches("\\d{4}-\\d{2}"));
    }

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

        verify(budgets).accrue(eq("exec-1"), anyString(), eq(120), eq(10_000), eq(50_000L));
    }

    @Test
    void otherEventsDoNotAccrue() {
        when(cursors.findLastSeq()).thenReturn(0L);

        writer.writeBatch(List.of(
                event(1, "NodeStarted", Map.of("attempt", 1)),
                event(2, "CommandCompleted", Map.of("callId", "c"))));

        verify(budgets, never()).accrue(anyString(), anyString(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void everyCompletedNodeAccruesOnce() {
        when(cursors.findLastSeq()).thenReturn(0L);

        writer.writeBatch(List.of(
                event(1, "NodeCompleted", Map.of("tokenUsed", 10)),
                event(2, "NodeCompleted", Map.of("tokenUsed", 20))));

        verify(budgets).accrue(eq("exec-1"), anyString(), eq(10), eq(10_000), eq(50_000L));
        verify(budgets).accrue(eq("exec-1"), anyString(), eq(20), eq(10_000), eq(50_000L));
        verify(budgets, times(2)).accrue(anyString(), anyString(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void cursorAdvancesToMaxSeq() {
        when(cursors.findLastSeq()).thenReturn(0L);

        long next = writer.writeBatch(List.of(
                event(5, "NodeStarted", Map.of()),
                event(9, "NodeCompleted", Map.of("tokenUsed", 1))));

        assertEquals(9L, next);
        verify(cursors).upsert(eq(9L));
        verify(nodes, times(2)).upsert(any()); // NodeStarted + NodeCompleted 各一行
    }

    @Test
    void nodeCompletedRecordsTheResolvedModel() {
        writer.writeBatch(List.of(event(4, "NodeCompleted", Map.of(
                "attempt", 1, "tokenUsed", 88,
                "output", Map.of("model", "deepseek-v4-pro", "text", "ok")))));

        ArgumentCaptor<NodeExecutionMapper.NodeRow> row =
                ArgumentCaptor.forClass(NodeExecutionMapper.NodeRow.class);
        verify(nodes).upsert(row.capture());
        assertEquals("deepseek-v4-pro", row.getValue().model());
    }

    @Test
    void nodeWithoutOutputModelLeavesModelEmpty() {
        // 非 LLM 节点、或输出溢出（D5）时 payload 里没有 output.model：留空而不是编一个
        writer.writeBatch(List.of(event(5, "NodeCompleted", Map.of(
                "attempt", 1, "tokenUsed", 4, "outputDigest", "d"))));

        ArgumentCaptor<NodeExecutionMapper.NodeRow> row =
                ArgumentCaptor.forClass(NodeExecutionMapper.NodeRow.class);
        verify(nodes).upsert(row.capture());
        assertEquals(null, row.getValue().model());
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
    void nodeFailedKeepsErrorMessage() {
        writer.writeBatch(List.of(event(7, "NodeFailed", Map.of(
                "attempt", 1,
                "errorCode", "PARALLEL-BRANCH-FAILED",
                "class", "PERMANENT",
                "message", "branch 'kb' failed: DSL-REF-001 unknown variable"))));

        ArgumentCaptor<NodeExecutionMapper.NodeRow> row =
                ArgumentCaptor.forClass(NodeExecutionMapper.NodeRow.class);
        verify(nodes).upsert(row.capture());
        assertEquals("PARALLEL-BRANCH-FAILED", row.getValue().errorCode());
        // 只有错误码的话，界面上看到的就是一串代码，排查还得回事件流翻
        assertEquals("branch 'kb' failed: DSL-REF-001 unknown variable",
                row.getValue().errorMessage());
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

    @Test
    void duplicateEventSeqDoesNotAccrueTwice() {
        // 同一事件 seq 被两个副本/重放投影时，INSERT IGNORE 返回 0，不重复计费
        when(cursors.findLastSeq()).thenReturn(0L);
        when(budgetSeen.claim(eq(1L), eq("exec-1"))).thenReturn(1, 0); // 首次成功，第二次重复

        writer.writeBatch(List.of(event(1, "NodeCompleted", Map.of("tokenUsed", 100))));
        writer.writeBatch(List.of(event(1, "NodeCompleted", Map.of("tokenUsed", 100))));

        verify(budgets, times(1)).accrue(eq("exec-1"), anyString(), eq(100), eq(10_000), eq(50_000L));
    }
}
