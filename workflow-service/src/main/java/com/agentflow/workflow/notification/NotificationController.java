package com.agentflow.workflow.notification;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.workflow.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知：按用户维度（不绑空间），因此不经 TenantFilter。
 * 内容只含 gate 提示词与执行 id，不含业务变量原文。
 */
@RestController
public class NotificationController {

    private final NotificationMapper notifications;

    public NotificationController(NotificationMapper notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/v1/notifications")
    public ApiResponse<Map<String, Object>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "50") int limit) {
        String userId = requireUser(request);
        int pageSize = Math.min(Math.max(limit, 1), 200);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", notifications.listForUser(userId, unreadOnly, pageSize));
        data.put("unread", notifications.countUnread(userId));
        return ApiResponse.ok(data);
    }

    @PostMapping("/api/v1/notifications/{id}/read")
    public ApiResponse<Map<String, Object>> read(HttpServletRequest request,
                                                 @PathVariable String id) {
        notifications.markRead(id, requireUser(request));
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PostMapping("/api/v1/notifications/read-all")
    public ApiResponse<Map<String, Object>> readAll(HttpServletRequest request) {
        notifications.markAllRead(requireUser(request));
        return ApiResponse.ok(Map.of("ok", true));
    }

    private static String requireUser(HttpServletRequest request) {
        Object userId = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (userId == null) {
            throw new BusinessException(ErrorCode.AGT_AUTH_001, "unauthenticated");
        }
        return userId.toString();
    }
}
