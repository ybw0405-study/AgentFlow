package com.agentflow.workflow.trigger;

import com.agentflow.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开 webhook 入口：不带 JWT，凭据是 URL 里的 token。
 *
 * <p>对外的语义刻意收窄：token 不对一律 404（不泄露存在性），停用是 409，
 * 签名不对是 403。请求体按 JSON 对象解析，字段直接作为本次执行的入参。
 */
@RestController
public class HookController {

    private final TriggerService service;

    public HookController(TriggerService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/hooks/{token}")
    public ApiResponse<TriggerService.FireResult> hook(
            @PathVariable String token,
            @RequestBody(required = false) String body,
            @RequestHeader(value = "X-AF-Signature", required = false) String signature,
            @RequestHeader(value = "X-AF-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-AF-Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(service.fireWebhook(token, body, signature, timestamp,
                idempotencyKey));
    }

    /** 健康探测：确认入口存在且未被网关拦掉，不触发任何执行。 */
    @PostMapping("/api/v1/hooks/{token}/probe")
    public ApiResponse<Map<String, Object>> probe(@PathVariable String token) {
        return ApiResponse.ok(service.describe(token));
    }
}
