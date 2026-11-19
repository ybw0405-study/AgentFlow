package com.agentflow.workflow.execution;

import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext;
import com.agentflow.common.trace.TraceIdContext;
import com.agentflow.workflow.sse.SseHub;
import org.springframework.beans.factory.annotation.Qualifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.task.TaskExecutor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 执行触发/查询/取消/SSE。触发入口在 /workflows/{id}/executions（§7 契约）。 */
@RestController
public class ExecutionController {

    private final ExecutionService service;
    private final WorkflowExecutionMapper executions;
    private final NodeExecutionMapper nodes;
    private final EngineGatewayClient engine;
    private final SseHub sseHub;
    private final TaskExecutor sseTaskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExecutionController(ExecutionService service, WorkflowExecutionMapper executions,
                               NodeExecutionMapper nodes, EngineGatewayClient engine,
                               SseHub sseHub, @Qualifier("sseTaskExecutor") TaskExecutor sseTaskExecutor) {
        this.service = service;
        this.executions = executions;
        this.nodes = nodes;
        this.engine = engine;
        this.sseHub = sseHub;
        this.sseTaskExecutor = sseTaskExecutor;
    }

    public record TriggerRequest(String versionId,
                                 @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
                                 Map<String, Object> variables,
                                 ExecutionService.PolicyRequest policy) {
    }

    public record TriggerData(String executionId, String status, boolean idempotentReplay,
                              String eventsUrl, String streamUrl) {
    }

    @PostMapping("/api/v1/workflows/{id}/executions")
    public ApiResponse<TriggerData> trigger(@PathVariable String id,
                                            @Validated @RequestBody TriggerRequest body) {
        var result = service.trigger(TenantContext.require(), id, body.versionId(),
                body.idempotencyKey(), body.variables(), body.policy(),
                TraceIdContext.currentOrCreate());
        String base = "/api/v1/executions/" + result.executionId();
        return ApiResponse.ok(new TriggerData(result.executionId(), result.status(),
                result.idempotentReplay(), base + "/events", base + "/events/stream"));
    }

    @GetMapping("/api/v1/executions/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        var tenant = TenantContext.require();
        var exec = executions.findByIdAndTenant(id, tenant.tenantId());
        if (exec == null) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("execution", exec);
        body.put("nodes", nodes.listByExecution(id));
        body.put("result", finalResult(id, exec.status()));
        return ApiResponse.ok(body);
    }

    /**
     * 终态执行的最终产出（end 节点 resultMapping 的结果）。
     *
     * <p>结果只存在事件流里，投影表没有这一列；引擎不可达时返回 null 而不是让整个详情接口失败——
     * 详情页的主体（状态、节点树）不该被附加信息拖垮。
     */
    private Object finalResult(String executionId, String status) {
        if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT").contains(status)) {
            return null;
        }
        try {
            return engine.executionState(executionId).get("result");
        } catch (RuntimeException e) {
            return null;
        }
    }

    @GetMapping("/api/v1/executions")
    public ApiResponse<PageResult<WorkflowExecutionMapper.ExecutionRow>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String definitionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        var tenant = TenantContext.require();
        int pageSize = Math.min(Math.max(limit, 1), 100);
        var rows = executions.listByTenant(tenant.tenantId(), status, definitionId,
                PageResult.decode(cursor), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        var items = hasMore ? rows.subList(0, pageSize) : rows;
        Long lastSeq = items.isEmpty() ? null : items.get(items.size() - 1).seq();
        return ApiResponse.ok(PageResult.of(items, lastSeq, hasMore));
    }

    @GetMapping("/api/v1/executions/{id}/events")
    public ApiResponse<EngineGatewayClient.EventsPage> events(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "500") int limit) {
        requireOwned(id);
        return ApiResponse.ok(engine.executionEvents(id, afterSeq, limit));
    }

    @GetMapping("/api/v1/executions/{id}/nodes/{key}/io")
    public ApiResponse<Map<String, Object>> nodeIo(@PathVariable String id,
                                                   @PathVariable String key) {
        requireOwned(id);
        return ApiResponse.ok(engine.nodeIo(id, key));
    }

    @PostMapping("/api/v1/executions/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable String id) {
        requireOwned(id);
        return ApiResponse.ok(engine.cancelExecution(id));
    }

    /** SSE：先发 afterSeq 之后的存量事件（追平），再注册到实时频道。 */
    @GetMapping("/api/v1/executions/{id}/events/stream")
    public SseEmitter stream(@PathVariable String id,
                             @RequestParam(defaultValue = "0") long afterSeq) {
        requireOwned(id);
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        sseTaskExecutor.execute(() -> {
            try {
                var page = engine.executionEvents(id, afterSeq, 500);
                for (var e : page.events()) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(e.seq()))
                            .name("engine-event")
                            .data(objectMapper.writeValueAsString(e)));
                }
                sseHub.register(id, emitter);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void requireOwned(String executionId) {
        var tenant = TenantContext.require();
        if (executions.findByIdAndTenant(executionId, tenant.tenantId()) == null) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
    }
}
