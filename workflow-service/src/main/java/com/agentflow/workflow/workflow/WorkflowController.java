package com.agentflow.workflow.workflow;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.tenant.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    public record CreateRequest(@NotBlank @Size(max = 128) String name,
                                @Size(max = 1024) String description) {
    }

    public record PatchRequest(@Size(max = 128) String name,
                               @Size(max = 1024) String description,
                               String status,
                               Integer maxConcurrent) {
    }

    public record VersionRefRequest(@NotBlank String versionId) {
    }

    @PostMapping
    public ApiResponse<WorkflowService.CreateResult> create(
            @Validated @RequestBody CreateRequest body) {
        return ApiResponse.ok(
                service.create(TenantContext.require(), body.name(), body.description()));
    }

    @GetMapping
    public ApiResponse<PageResult<WorkflowDefinitionMapper.DefinitionRow>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.list(TenantContext.require(), status, keyword, cursor,
                limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        var def = service.detail(TenantContext.require(), id);
        var versions = service.listVersions(TenantContext.require(), id);
        return ApiResponse.ok(Map.of(
                "definition", def,
                "versions", versions));
    }

    @PatchMapping("/{id}")
    public ApiResponse<Map<String, Object>> patch(@PathVariable String id,
                                                  @Validated @RequestBody PatchRequest body) {
        if (body.status() != null) {
            if ("ARCHIVED".equals(body.status())) {
                service.archive(TenantContext.require(), id);
            } else {
                throw new com.agentflow.common.error.BusinessException(
                        com.agentflow.common.error.ErrorCode.COM_VALIDATION_001,
                        "status only supports ARCHIVED");
            }
        }
        if (body.name() != null || body.description() != null) {
            service.updateMeta(TenantContext.require(), id,
                    body.name() != null ? body.name() : service.detail(TenantContext.require(), id).name(),
                    body.description());
        }
        if (body.maxConcurrent() != null) {
            service.setConcurrency(TenantContext.require(), id, body.maxConcurrent());
        }
        return ApiResponse.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        service.delete(TenantContext.require(), id);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PutMapping("/{id}/draft")
    public ApiResponse<Map<String, Object>> saveDraft(@PathVariable String id,
                                                      @RequestBody Map<String, Object> dsl) {
        return ApiResponse.ok(service.saveDraft(TenantContext.require(), id, dsl));
    }

    @PostMapping("/{id}/versions")
    public ApiResponse<WorkflowVersionMapper.VersionRow> freeze(@PathVariable String id) {
        return ApiResponse.ok(service.freeze(TenantContext.require(), id));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable String id,
                                                    @Validated @RequestBody VersionRefRequest body) {
        service.publish(TenantContext.require(), id, body.versionId());
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/rollback")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable String id,
                                                     @Validated @RequestBody VersionRefRequest body) {
        service.rollback(TenantContext.require(), id, body.versionId());
        return ApiResponse.ok(Map.of("ok", true));
    }
}
