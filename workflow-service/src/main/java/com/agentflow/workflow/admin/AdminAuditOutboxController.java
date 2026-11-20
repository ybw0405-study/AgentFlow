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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：审计投递队列水位与隔离区（平台 ADMIN）。
 *
 * <p>outbox 的设计前提是「积压可见」。没有水位就只能等审计对不上账才发现 broker
 * 早就挂了；DEAD 行更需要能列出、能重放，否则「隔离」就变成了另一种静默丢失。
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
        requireAdmin(request);
        Timestamp oldest = outbox.oldestPendingAt();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sink", delivery.primary().name());
        body.put("degradedSink", delivery.degraded().name());
        body.put("pending", outbox.pendingCount());
        body.put("retrying", outbox.retryingCount());
        body.put("dead", outbox.deadCount());
        body.put("oldestPendingAt", oldest == null ? null : oldest.toInstant().toString());
        body.put("oldestPendingAgeSeconds", oldest == null
                ? 0 : Math.max(0, Instant.now().getEpochSecond() - oldest.toInstant().getEpochSecond()));
        return ApiResponse.ok(body);
    }

    /** 隔离区明细：原始 message_json 原样返回，供人工判断原因。 */
    @GetMapping("/audit-outbox/dead")
    public ApiResponse<Map<String, Object>> dead(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit) {
        requireAdmin(request);
        int capped = Math.min(Math.max(limit, 1), 200);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", outbox.deadCount());
        body.put("items", outbox.dead(capped));
        return ApiResponse.ok(body);
    }

    /** 人工重放：清掉隔离与退避标记，下一轮 relay 会重新捞到这一行。 */
    @PostMapping("/audit-outbox/dead/{id}/replay")
    public ApiResponse<Map<String, Object>> replay(
            HttpServletRequest request, @PathVariable String id) {
        requireAdmin(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("replayed", outbox.replayDead(id));
        return ApiResponse.ok(body);
    }

    private static void requireAdmin(HttpServletRequest request) {
        var user = (AuthenticatedUser) request.getAttribute(JwtAuthFilter.ATTR_USER);
        if (user == null || !user.isAdmin()) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "platform admin required");
        }
    }
}
