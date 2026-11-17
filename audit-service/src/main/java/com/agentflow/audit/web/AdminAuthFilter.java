package com.agentflow.audit.web;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AuthenticatedUser;
import com.agentflow.common.security.JwtService;
import com.agentflow.common.web.FilterExceptionWriter;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** audit-service 认证：仅平台 ADMIN 可查审计。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public AdminAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            FilterExceptionWriter.write(response, ErrorCode.AGT_AUTH_001,
                    "missing bearer token");
            return;
        }
        AuthenticatedUser user;
        try {
            user = jwtService.verify(header.substring(7));
        } catch (JwtException | IllegalArgumentException e) {
            FilterExceptionWriter.write(response, ErrorCode.USR_TOKEN_001,
                    "token invalid or expired");
            return;
        }
        if (!user.isAdmin()) {
            FilterExceptionWriter.write(response, ErrorCode.WF_FORBIDDEN_001,
                    "platform admin required");
            return;
        }
        chain.doFilter(request, response);
    }
}
