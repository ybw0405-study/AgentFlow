package com.agentflow.gateway.filter;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AuthenticatedUser;
import com.agentflow.common.security.JwtService;
import com.agentflow.common.web.FilterExceptionWriter;
import com.agentflow.common.web.PublicPaths;
import com.agentflow.gateway.proxy.EnginePathPolicy;
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

/**
 * 网关认证：验签 Bearer；/engine/** 走内部令牌；/api/v1/admin/** 要求平台 ADMIN。
 * 认证信息以请求属性下传（下游服务同样独立验签，双保险）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewayAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USER = "af.user";

    private final JwtService jwtService;
    private final String internalToken;

    public GatewayAuthFilter(JwtService jwtService,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${agentflow.internal-token}") String internalToken) {
        this.jwtService = jwtService;
        this.internalToken = internalToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || PublicPaths.isPublic(path)
                || !(path.startsWith("/api/v1/") || path.startsWith("/engine/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // 内部引擎路由：先按控制面真实用到的「方法 + 路径」白名单收敛可达面（AF-3），
        // 再校验服务间令牌；两者任一不满足都回 404（不暴露路由存在性）。
        if (path.startsWith("/engine/")) {
            if (!EnginePathPolicy.isAllowed(request.getMethod(), path)) {
                FilterExceptionWriter.write(response, ErrorCode.WF_NOT_FOUND_001,
                        "resource not found");
                return;
            }
            String token = request.getHeader("X-Internal-Token");
            if (token == null || !java.security.MessageDigest.isEqual(
                    token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    internalToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                FilterExceptionWriter.write(response, ErrorCode.WF_NOT_FOUND_001,
                        "resource not found");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

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
        if (path.startsWith("/api/v1/admin/") && !user.isAdmin()) {
            FilterExceptionWriter.write(response, ErrorCode.WF_FORBIDDEN_001,
                    "platform admin required");
            return;
        }
        request.setAttribute(ATTR_USER, user);
        chain.doFilter(request, response);
    }
}
