package com.agentflow.workflow.admin;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AuthenticatedUser;
import com.agentflow.workflow.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：成本中心（平台 ADMIN）。
 *
 * <p>只做两件事：挡住非平台管理员，其余交给 {@link AdminUsageService}。口径计算留在服务层，
 * 因为这里的判断（限额继承、试跑份额、按模型聚合）都需要单测，而带着 HttpServletRequest
 * 的代码不好测。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminUsageController {

    private final AdminUsageService usage;

    public AdminUsageController(AdminUsageService usage) {
        this.usage = usage;
    }

    @GetMapping("/usage")
    public ApiResponse<Map<String, Object>> usage(HttpServletRequest request,
                                                  @RequestParam(required = false)
                                                  String period) {
        var user = (AuthenticatedUser) request.getAttribute(JwtAuthFilter.ATTR_USER);
        if (user == null || !user.isAdmin()) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "platform admin required");
        }
        return ApiResponse.ok(usage.usage(period));
    }
}
