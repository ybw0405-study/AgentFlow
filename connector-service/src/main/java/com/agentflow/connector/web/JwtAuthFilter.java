package com.agentflow.connector.web;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AuthenticatedUser;
import com.agentflow.common.security.JwtService;
import com.agentflow.common.web.FilterExceptionWriter;
import com.agentflow.common.web.PublicPaths;
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

/** JWT 认证：/api/v1/** 验签后注入 af.userId / af.user。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USER_ID = "af.userId";
    public static final String ATTR_USER = "af.user";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || PublicPaths.isPublic(path)
                || !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            FilterExceptionWriter.write(response, ErrorCode.AGT_AUTH_001, "missing bearer token");
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
        request.setAttribute(ATTR_USER_ID, user.userId());
        request.setAttribute(ATTR_USER, user);
        chain.doFilter(request, response);
    }
}
