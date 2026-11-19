package com.agentflow.workflow.admin;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AuthenticatedUser;
import com.agentflow.workflow.audit.AuditDelivery;
import com.agentflow.workflow.audit.AuditOutboxMapper;
import com.agentflow.workflow.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：审计投递队列水位（平台 ADMIN）。
 *
 * <p>为什么要暴露这个：outbox 的设计前提是「积压可见」。没有水位就只能等审计对不上账
 * 才发现 broker 早就挂了。pending 持续上涨 / oldestPendingAgeSeconds 变大 = MQ 或库出问题。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminAuditOutboxController {

    private final AuditOutboxMapper outbox;
    private final AuditDelivery delivery;

    public AdminAuditOutboxController(AuditOutboxMapper outbox, AuditDelivery delivery) {
        this.outbox = outbox;
        this.delivery = delivery;
    }

    @GetMapping("/audit-outbox")
    public ApiResponse<Map<String, Object>> stats(HttpServletRequest request) {
        var user = (AuthenticatedUser) request.getAttribute(JwtAuthFilter.ATTR_USER);
        if (user == null || !user.isAdmin()) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "platform admin required");
        }
        Timestamp oldest = outbox.oldestPendingAt();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sink", delivery.primary().name());
        body.put("degradedSink", delivery.degraded().name());
        body.put("pending", outbox.pendingCount());
        body.put("retrying", outbox.retryingCount());
        body.put("oldestPendingAt", oldest == null ? null : oldest.toInstant().toString());
        body.put("oldestPendingAgeSeconds", oldest == null
                ? 0 : Math.max(0, Instant.now().getEpochSecond() - oldest.toInstant().getEpochSecond()));
        return ApiResponse.ok(body);
    }
}
