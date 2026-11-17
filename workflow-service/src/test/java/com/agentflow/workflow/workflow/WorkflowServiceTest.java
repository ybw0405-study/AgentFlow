package com.agentflow.workflow.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.workflow.engine.EngineGatewayClient.DslErrorItem;
import com.agentflow.workflow.engine.EngineGatewayClient.DslValidationResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

class WorkflowServiceTest {

    private WorkflowDefinitionMapper definitions;
    private WorkflowVersionMapper versions;
    private EngineGatewayClient engine;
    private AuditPublisher audit;
    private WorkflowService service;

    private final TenantInfo editor = new TenantInfo("space-1", "user-1", "EDITOR");
    private final TenantInfo viewer = new TenantInfo("space-1", "user-2", "VIEWER");

    @BeforeEach
    void setUp() {
        definitions = Mockito.mock(WorkflowDefinitionMapper.class);
        versions = Mockito.mock(WorkflowVersionMapper.class);
        engine = Mockito.mock(EngineGatewayClient.class);
        audit = Mockito.mock(AuditPublisher.class);
        service = new WorkflowService(definitions, versions, engine, audit);
    }

    private WorkflowDefinitionMapper.DefinitionRow defRow(String id, String draft) {
        return new WorkflowDefinitionMapper.DefinitionRow(
                1L, id, "space-1", "wf", null, "DRAFT", null, draft, null, "user-1",
                null, null);
    }

    @Test
    void createDuplicateNameConflict() {
        when(definitions.insert(any())).thenThrow(new DuplicateKeyException("dup"));
        var ex = assertThrows(BusinessException.class,
                () -> service.create(editor, "wf", null));
        assertEquals(ErrorCode.COM_CONFLICT_001, ex.errorCode());
    }

    @Test
    void saveDraftWithEngineErrorsReturns422() {
        when(definitions.findByIdAndTenant("d1", "space-1")).thenReturn(defRow("d1", "{}"));
        when(engine.validateDsl(any())).thenReturn(new DslValidationResponse(
                false, List.of(new DslErrorItem("DSL-CYCLE-001", "cycle", "a", null)),
                null, null));
        var ex = assertThrows(BusinessException.class,
                () -> service.saveDraft(editor, "d1", Map.of("nodes", List.of())));
        assertEquals(ErrorCode.DSL_CYCLE_001, ex.errorCode());
        assertEquals(422, ex.errorCode().status().value());
        assertEquals(1, ex.fieldErrors().size());
        verify(definitions, never()).updateDraft(any(), any(), any(), any());
    }

    @Test
    void freezeWithoutDraftRejected() {
        when(definitions.findByIdAndTenant("d1", "space-1")).thenReturn(defRow("d1", null));
        var ex = assertThrows(BusinessException.class, () -> service.freeze(editor, "d1"));
        assertEquals(ErrorCode.WF_STATE_001, ex.errorCode());
    }

    @Test
    void freezeAssignsNextVersionNo() {
        var def = defRow("d1", "{\"dslVersion\":\"1.0\"}");
        when(definitions.findByIdAndTenant("d1", "space-1")).thenReturn(def);
        when(engine.validateDsl(any())).thenReturn(new DslValidationResponse(
                true, List.of(), Map.of("dslVersion", "1.0"), "checksum-1"));
        when(versions.maxVersionNo("d1")).thenReturn(2);
        when(versions.findById(any())).thenAnswer(inv -> new WorkflowVersionMapper.VersionRow(
                inv.getArgument(0), "d1", 3, "{}", "checksum-1", null, null, "user-1", null));
        var v = service.freeze(editor, "d1");
        assertEquals(3, v.versionNo());
        verify(versions).insert(any());
        verify(audit).publish(any());
    }

    @Test
    void publishVersionOfOtherDefinitionIs404() {
        when(definitions.findByIdAndTenant("d1", "space-1")).thenReturn(defRow("d1", "{}"));
        when(versions.findById("v9")).thenReturn(new WorkflowVersionMapper.VersionRow(
                "v9", "OTHER", 1, "{}", "c", null, null, "u", null));
        var admin = new TenantInfo("space-1", "admin-1", "ADMIN");
        var ex = assertThrows(BusinessException.class,
                () -> service.publish(admin, "d1", "v9"));
        assertEquals(ErrorCode.WF_NOT_FOUND_001, ex.errorCode());
    }

    @Test
    void viewerCannotFreeze() {
        var ex = assertThrows(BusinessException.class, () -> service.freeze(viewer, "d1"));
        assertEquals(ErrorCode.WF_FORBIDDEN_001, ex.errorCode());
        verify(audit).publish(any());  // 越权尝试进审计
    }

    @Test
    void rollbackDoesNotOverwritePublishMetadata() {
        when(definitions.findByIdAndTenant("d1", "space-1")).thenReturn(defRow("d1", "{}"));
        when(versions.findById("v1")).thenReturn(new WorkflowVersionMapper.VersionRow(
                "v1", "d1", 1, "{}", "c", null, "original-publisher", "u", null));
        var admin = new TenantInfo("space-1", "admin-1", "ADMIN");

        service.rollback(admin, "d1", "v1");

        verify(definitions).updateCurrentVersion("d1", "space-1", "v1");
        // 回滚不得重新 markPublished 覆盖原发布人/时间
        verify(versions, never()).markPublished(any(), any());
    }

    @Test
    void rollbackByViewerRejected() {
        var ex = assertThrows(BusinessException.class,
                () -> service.rollback(viewer, "d1", "v1"));
        assertEquals(ErrorCode.WF_FORBIDDEN_001, ex.errorCode());
    }

    @Test
    void crossTenantDefinitionIs404() {
        when(definitions.findByIdAndTenant("d1", "space-1")).thenReturn(null);
        var ex = assertThrows(BusinessException.class,
                () -> service.detail(editor, "d1"));
        assertEquals(ErrorCode.WF_NOT_FOUND_001, ex.errorCode());
        assertTrue(ex.getMessage().contains("not found"));
    }
}
