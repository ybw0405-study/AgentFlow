package com.agentflow.audit.web;

import com.agentflow.audit.repo.AuditEventMapper;
import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.security.JwtService;
import com.agentflow.common.web.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计查询：cursor 倒序分页（平台 ADMIN，过滤器已强制）。 */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {

    private final AuditEventMapper mapper;

    public AdminAuditController(AuditEventMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public ApiResponse<PageResult<AuditEventMapper.AuditRow>> query(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        int pageSize = Math.min(Math.max(limit, 1), 500);
        long beforeSeq = cursor == null || cursor.isBlank()
                ? Long.MAX_VALUE : PageResult.decode(cursor);
        var rows = mapper.query(traceId, eventType, tenantId, beforeSeq, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<AuditEventMapper.AuditRow> items = hasMore ? rows.subList(0, pageSize) : rows;
        // 倒序游标：nextCursor 编码的是本页最小 seq（下一页 seq < 它）
        Long lastSeq = items.isEmpty() ? null : items.get(items.size() - 1).seq();
        String next = hasMore && lastSeq != null ? PageResult.encode(lastSeq) : null;
        return ApiResponse.ok(new PageResult<>(items, next));
    }
}

@Configuration
class AuditWebConfiguration {

    @Bean
    JwtService jwtService(@Value("${agentflow.jwt.secret}") String secret) {
        return new JwtService(secret, 0);
    }

    @Bean
    TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
