package com.agentflow.workflow.tenant;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.common.tenant.TenantHierarchyResolver;
import com.agentflow.common.web.FilterExceptionWriter;
import com.agentflow.workflow.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 租户解析：业务路径必须带 X-Space-Id；按三级继承（ORG → TEAM → SPACE）解析有效角色后
 * 写入 TenantContext。无成员关系一律 404（不泄露资源存在性，§5.3）。
 */
@Component
@Order(30)
public class TenantFilter extends OncePerRequestFilter {

    public static final String HEADER_SPACE = "X-Space-Id";

    private final TenantHierarchyResolver resolver;

    public TenantFilter(MybatisTenantTreePort port) {
        this.resolver = new TenantHierarchyResolver(port);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/v1/workflows") || path.startsWith("/api/v1/executions")
                || path.startsWith("/api/v1/connectors") || path.startsWith("/api/v1/gates")
                || path.startsWith("/api/v1/evals") || path.startsWith("/api/v1/knowledge"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String userId = (String) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        String spaceId = request.getHeader(HEADER_SPACE);
        if (userId == null || spaceId == null || spaceId.isBlank()) {
            FilterExceptionWriter.write(response, ErrorCode.WF_NOT_FOUND_001,
                    "resource not found");
            return;
        }
        var resolved = resolver.resolve(spaceId, userId);
        if (resolved.isEmpty()) {
            // 跨租户/非成员：404 而非 403
            FilterExceptionWriter.write(response, ErrorCode.WF_NOT_FOUND_001,
                    "resource not found");
            return;
        }
        TenantInfo info = TenantInfo.of(resolved.get());
        TenantContext.set(info);
        request.setAttribute("af.tenant", info);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
