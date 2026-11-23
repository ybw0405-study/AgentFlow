package com.agentflow.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.tenant.TenantContext;
import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.workflow.sse.SseHub;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

/** 追踪链接是运行时配置下发：未配置时详情响应不得多出任何键。 */
class ExecutionControllerTest {

    private WorkflowExecutionMapper executions;
    private NodeExecutionMapper nodes;

    @BeforeEach
    void setUp() {
        executions = mock(WorkflowExecutionMapper.class);
        nodes = mock(NodeExecutionMapper.class);
        TenantContext.set(new TenantContext.TenantInfo("tenant-a", "user-1", "EDITOR"));
        var row = new WorkflowExecutionMapper.ExecutionRow(
                1L, "exec-1", "def-1", "ver-1", "tenant-a", "user-1", "RUNNING",
                "idem-1", "a".repeat(32), null, null, null, 0, 0, null, null, null, null);
        when(executions.findByIdAndTenant("exec-1", "tenant-a")).thenReturn(row);
        when(nodes.listByExecution("exec-1")).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ExecutionController controller(String langfuseBaseUrl) {
        return new ExecutionController(mock(ExecutionService.class), executions, nodes,
                mock(EngineGatewayClient.class), mock(SseHub.class),
                mock(TaskExecutor.class), langfuseBaseUrl);
    }

    @Test
    void detailCarriesLangfuseBaseUrlWhenConfigured() {
        ApiResponse<Map<String, Object>> resp =
                controller("http://127.0.0.1:13000/project/p1/traces").detail("exec-1");
        assertEquals("OK", resp.code());
        assertEquals("http://127.0.0.1:13000/project/p1/traces", resp.data().get("langfuseBaseUrl"));
    }

    @Test
    void detailOmitsLangfuseBaseUrlWhenUnconfigured() {
        ApiResponse<Map<String, Object>> resp = controller("").detail("exec-1");
        assertEquals("OK", resp.code());
        assertFalse(resp.data().containsKey("langfuseBaseUrl"));
        assertEquals(3, resp.data().size());
    }
}