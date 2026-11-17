package com.agentflow.connector.web;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.web.FilterExceptionWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** /internal/** 服务间令牌校验；失败按 404 处理（不暴露端点存在性）。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class InternalTokenFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public InternalTokenFilter(@Value("${agentflow.internal-token}") String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader("X-Internal-Token");
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expected)) {
            FilterExceptionWriter.write(response, ErrorCode.WF_NOT_FOUND_001,
                    "resource not found");
            return;
        }
        chain.doFilter(request, response);
    }
}
