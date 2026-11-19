package com.agentflow.workflow.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.workflow.execution.WorkflowExecutionMapper;
import java.sql.Timestamp;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 审批裁决：审批人校验、幂等、404 语义、引擎不可达落 outbox。 */
class GateServiceTest {

    private final GateTaskMapper gates = mock(GateTaskMapper.class);
    private final WorkflowExecutionMapper executions = mock(WorkflowExecutionMapper.class);
    private final GateResolveOutboxMapper outbox = mock(GateResolveOutboxMapper.class);
    private final EngineGatewayClient engine = mock(EngineGatewayClient.class);
    private final AuditPublisher audit = mock(AuditPublisher.class);
    private final GateDecisionPublisher decisions = mock(GateDecisionPublisher.class);
    private final GateService service = new GateService(gates, executions, outbox, engine, audit,
            decisions);

    private static WorkflowExecutionMapper.ExecutionBrief brief() {
        return new WorkflowExecutionMapper.ExecutionBrief("exec-1", "space-a", "def-1", "v1");
    }

    private static TenantInfo tenant(String userId, String role) {
        return new TenantInfo("space-a", userId, role, null, "DIRECT");
    }

    private static GateTaskMapper.GateRow gate(String approversJson, String status) {
        return new GateTaskMapper.GateRow(1L, "gate-1", "exec-1", "gate", "space-a", "def-1",
                "确认？", approversJson, "{}", status, null, null, null,
                new Timestamp(System.currentTimeMillis()), "approved",
                new Timestamp(System.currentTimeMillis()));
    }

    @Test
    void rejectsNonApprover() {
        when(executions.findBrief("exec-1")).thenReturn(brief());
        when(gates.findOne("exec-1", "gate")).thenReturn(gate("[\"u-2\"]", "PENDING"));

        var error = assertThrows(BusinessException.class,
                () -> service.resolve(tenant("u-1", "EDITOR"), "exec-1", "gate", "APPROVED", null));
        assertEquals(ErrorCode.WF_FORBIDDEN_001, error.errorCode());
        verify(engine, never()).resolveGate(anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void adminCanResolveEvenIfNotInSnapshot() {
        when(executions.findBrief("exec-1")).thenReturn(brief());
        when(gates.findOne("exec-1", "gate")).thenReturn(gate("[\"u-2\"]", "PENDING"));
        when(engine.resolveGate("exec-1", "gate", "APPROVED", "u-1", "ok"))
                .thenReturn(Map.of("branch", "approved", "status", "RESOLVED"));

        var result = service.resolve(tenant("u-1", "ADMIN"), "exec-1", "gate", "approved", "ok");
        assertEquals("RESOLVED", result.get("status"));
        assertEquals("approved", result.get("branch"));
    }

    @Test
    void unknownExecutionIs404Not403() {
        when(executions.findBrief("exec-x")).thenReturn(null);
        var error = assertThrows(BusinessException.class,
                () -> service.resolve(tenant("u-1", "ADMIN"), "exec-x", "gate", "APPROVED", null));
        assertEquals(ErrorCode.WF_NOT_FOUND_001, error.errorCode());
    }

    @Test
    void alreadyResolvedGateIsIdempotent() {
        when(executions.findBrief("exec-1")).thenReturn(brief());
        when(gates.findOne("exec-1", "gate")).thenReturn(gate("[\"u-1\"]", "APPROVED"));

        var result = service.resolve(tenant("u-1", "EDITOR"), "exec-1", "gate", "APPROVED", null);
        assertEquals("ALREADY_RESOLVED", result.get("status"));
        verify(engine, never()).resolveGate(anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void rejectsUnknownDecision() {
        when(executions.findBrief("exec-1")).thenReturn(brief());
        when(gates.findOne("exec-1", "gate")).thenReturn(gate("[\"u-1\"]", "PENDING"));

        var error = assertThrows(BusinessException.class,
                () -> service.resolve(tenant("u-1", "EDITOR"), "exec-1", "gate", "MAYBE", null));
        assertEquals(ErrorCode.COM_VALIDATION_001, error.errorCode());
    }

    @Test
    void engineUnreachableFallsBackToOutbox() {
        when(executions.findBrief("exec-1")).thenReturn(brief());
        when(gates.findOne("exec-1", "gate")).thenReturn(gate("[\"u-1\"]", "PENDING"));
        when(engine.resolveGate(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine unreachable"));

        var result = service.resolve(tenant("u-1", "EDITOR"), "exec-1", "gate", "REJECTED", "no");
        assertEquals("DEFERRED", result.get("status"));
        assertEquals(true, result.get("deferred"));
        verify(outbox).enqueue(any());
    }

    @Test
    void engineStateConflictIsPropagatedNotQueued() {
        when(executions.findBrief("exec-1")).thenReturn(brief());
        when(gates.findOne("exec-1", "gate")).thenReturn(gate("[\"u-1\"]", "PENDING"));
        when(engine.resolveGate(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.WF_STATE_001, "gate not pending"));

        var error = assertThrows(BusinessException.class,
                () -> service.resolve(tenant("u-1", "EDITOR"), "exec-1", "gate", "APPROVED", null));
        assertEquals(ErrorCode.WF_STATE_001, error.errorCode());
        verify(outbox, never()).enqueue(any());
        verify(audit, never()).publish(any());
    }

    @Test
    void viewerCannotResolveEvenIfListed() {
        when(executions.findBrief("exec-1")).thenReturn(brief());
        when(gates.findOne("exec-1", "gate")).thenReturn(gate("[\"u-1\"]", "PENDING"));

        var error = assertThrows(BusinessException.class,
                () -> service.resolve(tenant("u-1", "VIEWER"), "exec-1", "gate", "APPROVED", null));
        assertEquals(ErrorCode.WF_FORBIDDEN_001, error.errorCode());
        verify(engine, never()).resolveGate(eq("exec-1"), eq("gate"), anyString(),
                anyString(), any());
    }
}
