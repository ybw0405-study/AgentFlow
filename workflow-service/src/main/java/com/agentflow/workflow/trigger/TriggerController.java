package com.agentflow.workflow.trigger;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 触发器的管理入口（需要登录 + 编辑角色）。公开触发入口见 {@link HookController}。 */
@RestController
public class TriggerController {

    private final TriggerService service;

    public TriggerController(TriggerService service) {
        this.service = service;
    }

    public record CreateRequest(String type, String cron, Map<String, Object> variables) {
    }

    public record PatchRequest(Boolean enabled, String cron, Map<String, Object> variables) {
    }

    @GetMapping("/api/v1/workflows/{id}/triggers")
    public ApiResponse<List<TriggerService.TriggerView>> list(@PathVariable String id) {
        return ApiResponse.ok(service.list(TenantContext.require(), id));
    }

    @PostMapping("/api/v1/workflows/{id}/triggers")
    public ApiResponse<TriggerService.TriggerView> create(@PathVariable String id,
                                                          @RequestBody(required = false)
                                                          CreateRequest body) {
        CreateRequest request = body == null ? new CreateRequest("WEBHOOK", null, null) : body;
        return ApiResponse.ok(service.create(TenantContext.require(), id, request.type(),
                request.cron(), request.variables()));
    }

    @PatchMapping("/api/v1/triggers/{triggerId}")
    public ApiResponse<TriggerService.TriggerView> patch(@PathVariable String triggerId,
                                                         @Validated @RequestBody
                                                         PatchRequest body) {
        return ApiResponse.ok(service.patch(TenantContext.require(), triggerId, body.enabled(),
                body.cron(), body.variables()));
    }

    @PostMapping("/api/v1/triggers/{triggerId}/rotate")
    public ApiResponse<TriggerService.TriggerView> rotate(@PathVariable String triggerId) {
        return ApiResponse.ok(service.rotate(TenantContext.require(), triggerId));
    }

    @DeleteMapping("/api/v1/triggers/{triggerId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String triggerId) {
        service.delete(TenantContext.require(), triggerId);
        return ApiResponse.ok(Map.of("ok", true));
    }
}
