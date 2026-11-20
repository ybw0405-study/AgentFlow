package com.agentflow.workflow.execution;

import com.agentflow.workflow.engine.EngineGatewayClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.workflow.audit.AuditMessage;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import com.agentflow.workflow.workflow.WorkflowVersionMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ExecutionServiceTest {

    private WorkflowExecutionMapper executions;
    private WorkflowDefinitionMapper definitions;
    private WorkflowVersionMapper versions;
    private EngineGatewayClient engine;
    private AuditPublisher audit;
    private UsageService usage;
    private ExecutionService service;

    private final TenantInfo editor = new TenantInfo("space-1", "user-1", "EDITOR");
    private final TenantInfo viewer = new TenantInfo("space-1", "user-2", "VIEWER");

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        executions = Mockito.mock(WorkflowExecutionMapper.class);
        definitions = Mockito.mock(WorkflowDefinitionMapper.class);
        versions = Mockito.mock(WorkflowVersionMapper.class);
        engine = Mockito.mock(EngineGatewayClient.class);
        audit = Mockito.mock(AuditPublisher.class);
        CanaryService canary = Mockito.mock(CanaryService.class);  // route() 默认 null → 走 current
        usage = Mockito.mock(UsageService.class);
        service = new ExecutionService(executions, definitions, versions, engine, audit, canary,
                usage, 50_000, 50, 120_000, "mock.search,mock.echo,mock.flaky");
    }

    private void givenDefinitionAndVersion() {
        when(definitions.findByIdAndTenant("d1", "space-1"))
                .thenReturn(new WorkflowDefinitionMapper.DefinitionRow(
                        1L, "d1", "space-1", "wf", null, "PUBLISHED", "v1", "{}", null,
                        "user-1", null, null));
        when(versions.findById("v1")).thenReturn(new WorkflowVersionMapper.VersionRow(
                "v1", "d1", 1, "{\"dslVersion\":\"1.0\"}", "c", null, null, "user-1", null));
    }

    @Test
    void happyPathDispatchesToEngine() {
        givenDefinitionAndVersion();
        var result = service.trigger(editor, "d1", null, "idem-12345678",
                Map.of("question", "q"), null, "trace-1");
        assertEquals("PENDING", result.status());
        verify(executions).insert(any());
        verify(engine).submitExecution(any());
        verify(audit).publish(any());
    }

    @Test
    void idempotentReplayReturnsExistingWithoutDispatch() {
        givenDefinitionAndVersion();  // 定义存在是前置校验
        when(executions.findByIdempotency("space-1", "idem-12345678"))
                .thenReturn(new WorkflowExecutionMapper.ExecutionRow(
                        7L, "exec-existing", "d1", "v1", "space-1", "user-1", "RUNNING",
                        "idem-12345678", "t", 8000, 30, null, 0, 0, null, null, null, null));
        var result = service.trigger(editor, "d1", null, "idem-12345678",
                Map.of(), null, "trace-1");
        assertTrue(result.idempotentReplay());
        assertEquals("exec-existing", result.executionId());
        verify(engine, never()).submitExecution(any());
        verify(executions, never()).insert(any());
    }

    @Test
    void viewerTriggerIs403AndAudited() {
        var ex = assertThrows(BusinessException.class, () -> service.trigger(
                viewer, "d1", null, "idem-12345678", Map.of(), null, "trace-1"));
        assertEquals(ErrorCode.WF_FORBIDDEN_001, ex.errorCode());
        var captor = ArgumentCaptor.forClass(AuditMessage.class);
        verify(audit).publish(captor.capture());
        assertEquals("WF_FORBIDDEN_ATTEMPT", captor.getValue().eventType());
        verify(engine, never()).submitExecution(any());
    }

    @Test
    void crossTenantDefinitionIs404() {
        when(definitions.findByIdAndTenant("d1", "space-1")).thenReturn(null);
        var ex = assertThrows(BusinessException.class, () -> service.trigger(
                editor, "d1", null, "idem-12345678", Map.of(), null, "trace-1"));
        assertEquals(ErrorCode.WF_NOT_FOUND_001, ex.errorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void policyClampedToTenantLimitAndAudited() {
        givenDefinitionAndVersion();
        service.trigger(editor, "d1", null, "idem-12345678", Map.of(),
                new ExecutionService.PolicyRequest(9_999_999, null, null), "trace-1");
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(engine).submitExecution(captor.capture());
        Map<String, Object> policy =
                (Map<String, Object>) captor.getValue().get("policy");
        assertEquals(50_000, policy.get("tokenBudget"));
        var auditCaptor = ArgumentCaptor.forClass(AuditMessage.class);
        verify(audit, Mockito.atLeastOnce()).publish(auditCaptor.capture());
        assertTrue(auditCaptor.getAllValues().stream()
                .anyMatch(m -> "WF_POLICY_CLAMPED".equals(m.eventType())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void piiMaskedBeforeDispatch() {
        givenDefinitionAndVersion();
        service.trigger(editor, "d1", null, "idem-12345678",
                Map.of("question", "我的手机 13812345678 怎么了"), null, "trace-1");
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(engine).submitExecution(captor.capture());
        Map<String, Object> vars =
                (Map<String, Object>) captor.getValue().get("initialVariables");
        String question = (String) vars.get("question");
        assertTrue(question.contains("[PHONE]"));
        assertTrue(question.indexOf("13812345678") < 0);
    }

    @Test
    void engineDispatchFailureMarksExecutionFailed() {
        givenDefinitionAndVersion();
        Mockito.doThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_001, "down"))
                .when(engine).submitExecution(any());
        var ex = assertThrows(BusinessException.class, () -> service.trigger(
                editor, "d1", null, "idem-12345678", Map.of(), null, "trace-1"));
        assertEquals(ErrorCode.AGT_UPSTREAM_001, ex.errorCode());
        verify(executions).markFailedDispatch(anyString(), eq("FAILED"),
                eq("AGT-UPSTREAM-001"));
    }

    @Test
    void noPublishedVersionIs409() {
        when(definitions.findByIdAndTenant("d1", "space-1"))
                .thenReturn(new WorkflowDefinitionMapper.DefinitionRow(
                        1L, "d1", "space-1", "wf", null, "DRAFT", null, "{}", null,
                        "user-1", null, null));
        var ex = assertThrows(BusinessException.class, () -> service.trigger(
                editor, "d1", null, "idem-12345678", Map.of(), null, "trace-1"));
        assertEquals(ErrorCode.WF_STATE_001, ex.errorCode());
    }

    @Test
    void previewRecordsUsageWithTargetNodeModel() {
        givenDefinitionAndVersion();
        when(engine.previewNode(any())).thenReturn(Map.of(
                "status", "SUCCEEDED", "totalTokens", 321, "latencyMs", 1200,
                "trace", java.util.List.of(Map.of("key", "start"), Map.of("key", "draft"))));
        Map<String, Object> dsl = Map.of("nodes", java.util.List.of(
                Map.of("key", "draft", "type", "llm",
                        "config", Map.of("model", "deepseek-chat"))));

        service.preview(editor, "d1", "draft", dsl, Map.of());

        var captor = ArgumentCaptor.forClass(UsageService.PreviewRecord.class);
        verify(usage).recordPreview(eq("space-1"), captor.capture());
        assertEquals(321, captor.getValue().tokenUsed());
        assertEquals("deepseek-chat", captor.getValue().model());
        assertEquals(2, captor.getValue().nodeCount());
        assertEquals("SUCCEEDED", captor.getValue().status());
        assertEquals(1200, captor.getValue().latencyMs());
    }

    @Test
    void previewStillReturnsResultWhenAccountingFails() {
        givenDefinitionAndVersion();
        when(engine.previewNode(any())).thenReturn(Map.of(
                "status", "SUCCEEDED", "totalTokens", 10));
        Mockito.doThrow(new IllegalStateException("db down"))
                .when(usage).recordPreview(anyString(), any());

        Map<String, Object> result = service.preview(editor, "d1", "draft",
                Map.of("nodes", java.util.List.of()), Map.of());

        assertEquals("SUCCEEDED", result.get("status"));
        verify(usage).recordPreview(anyString(), any());
    }
}
