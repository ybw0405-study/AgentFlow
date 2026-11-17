package com.agentflow.workflow.gate;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.tenant.TenantContext;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审批待办与裁决（P3）。 */
@RestController
public class GateController {

    private final GateService service;

    public GateController(GateService service) {
        this.service = service;
    }

    public record ResolveRequest(@NotBlank String decision, String comment) {
    }

    @GetMapping("/api/v1/gates")
    public ApiResponse<List<Map<String, Object>>> inbox(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(service.inbox(TenantContext.require(), limit));
    }

    @GetMapping("/api/v1/executions/{id}/gates")
    public ApiResponse<List<GateTaskMapper.GateRow>> byExecution(@PathVariable String id) {
        return ApiResponse.ok(service.byExecution(TenantContext.require(), id));
    }

    @PostMapping("/api/v1/executions/{id}/gates/{nodeKey}/resolve")
    public ApiResponse<Map<String, Object>> resolve(@PathVariable String id,
                                                    @PathVariable String nodeKey,
                                                    @Validated @RequestBody ResolveRequest body) {
        return ApiResponse.ok(service.resolve(TenantContext.require(), id, nodeKey,
                body.decision(), body.comment()));
    }
}
