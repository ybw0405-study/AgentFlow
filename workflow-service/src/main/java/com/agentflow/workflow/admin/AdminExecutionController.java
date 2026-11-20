package com.agentflow.workflow.admin;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AuthenticatedUser;
import com.agentflow.workflow.execution.ExecutionReaper;
import com.agentflow.workflow.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：执行看护（平台 ADMIN）。
 *
 * <p>两个端点读的是同一套候选口径：{@code GET} 回答「现在有哪些执行卡着」，{@code POST}
 * 立刻跑一轮看护并回报结果。让运维手动触发，是因为定时轮询是 60 秒一次——线上排查时
 * 「等一分钟看看」比「点一下看看」难受得多，而这条链路本身幂等，多点几次没有副作用。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminExecutionController {

    private final ExecutionReaper reaper;

    public AdminExecutionController(ExecutionReaper reaper) {
        this.reaper = reaper;
    }

    @GetMapping("/executions/stale")
    public ApiResponse<List<ExecutionReaper.StaleView>> stale(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") int limit) {
        requireAdmin(request);
        return ApiResponse.ok(reaper.staleViews(limit));
    }

    @PostMapping("/executions/sweep")
    public ApiResponse<ExecutionReaper.SweepReport> sweep(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(reaper.sweep());
    }

    private void requireAdmin(HttpServletRequest request) {
        var user = (AuthenticatedUser) request.getAttribute(JwtAuthFilter.ATTR_USER);
        if (user == null || !user.isAdmin()) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "platform admin required");
        }
    }
}
