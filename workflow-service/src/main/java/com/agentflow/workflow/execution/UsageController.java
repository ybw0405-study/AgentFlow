package com.agentflow.workflow.execution;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.tenant.TenantContext;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户自己的用量：正式执行、试跑、预算余量各是多少。
 *
 * <p>与 {@code /api/v1/admin/usage} 的区别：那个是平台管理员看所有租户，这个只看当前空间，
 * 且必须能解释「为什么额度少了」——所以带最近试跑明细。
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private final UsageService usage;

    public UsageController(UsageService usage) {
        this.usage = usage;
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(
            @RequestParam(name = "days", defaultValue = "30") int days) {
        return ApiResponse.ok(usage.summary(TenantContext.require(), days));
    }
}
