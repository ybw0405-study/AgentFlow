package com.agentflow.connector.web;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.common.tenant.TenantHierarchyResolver;
import com.agentflow.common.web.FilterExceptionWriter;
import com.agentflow.connector.tenant.JdbcTenantTreePort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 连接器 API 的租户解析：X-Space-Id + 三级继承；非成员 404。 */
@Component
@Order(30)
public class TenantFilter extends OncePerRequestFilter {

    public static final String HEADER_SPACE = "X-Space-Id";

    private final TenantHierarchyResolver resolver;

    public TenantFilter(JdbcTenantTreePort port) {
        this.resolver = new TenantHierarchyResolver(port);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/connectors");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String userId = (String) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        String spaceId = request.getHeader(HEADER_SPACE);
        var resolved = (userId == null || spaceId == null || spaceId.isBlank())
                ? java.util.Optional.<TenantHierarchyResolver.Resolved>empty()
                : resolver.resolve(spaceId, userId);
        if (resolved.isEmpty()) {
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
