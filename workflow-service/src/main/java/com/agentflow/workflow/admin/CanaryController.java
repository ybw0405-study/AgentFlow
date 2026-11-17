package com.agentflow.workflow.admin;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AuthenticatedUser;
import com.agentflow.workflow.execution.CanaryService;
import com.agentflow.workflow.security.JwtAuthFilter;
import com.agentflow.workflow.tenant.TenantFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端（P1）：工作流版本灰度配置 / 查询 / 一键回滚。仅平台 ADMIN。 */
@RestController
@RequestMapping("/api/v1/admin/canary")
public class CanaryController {

    private final CanaryService canary;

    public CanaryController(CanaryService canary) {
        this.canary = canary;
    }

    public record ConfigureRequest(String primaryVersionId, String canaryVersionId, int percent) {}

    @PostMapping("/{definitionId}")
    public ApiResponse<CanaryService.Config> configure(
            HttpServletRequest request, @PathVariable String definitionId,
            @RequestBody ConfigureRequest body) {
        requireAdmin(request);
        return ApiResponse.ok(canary.configure(definitionId, body.primaryVersionId(),
                body.canaryVersionId(), body.percent(), space(request)));
    }

    @GetMapping("/{definitionId}")
    public ApiResponse<CanaryService.Config> get(
            HttpServletRequest request, @PathVariable String definitionId) {
        requireAdmin(request);
        return ApiResponse.ok(canary.get(definitionId, space(request)));
    }

    @PostMapping("/{definitionId}/rollback")
    public ApiResponse<CanaryService.Config> rollback(
            HttpServletRequest request, @PathVariable String definitionId) {
        requireAdmin(request);
        return ApiResponse.ok(canary.rollback(definitionId, space(request)));
    }

    private void requireAdmin(HttpServletRequest request) {
        var user = (AuthenticatedUser) request.getAttribute(JwtAuthFilter.ATTR_USER);
        if (user == null || !user.isAdmin()) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "platform admin required");
        }
    }

    private String space(HttpServletRequest request) {
        String space = request.getHeader(TenantFilter.HEADER_SPACE);
        if (space == null || space.isBlank()) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "X-Space-Id required");
        }
        return space;
    }
}
