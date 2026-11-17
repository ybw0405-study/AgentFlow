package com.agentflow.gateway.filter;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AuthenticatedUser;
import com.agentflow.common.web.FilterExceptionWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 粗粒度限流（Redis 固定窗口）：
 * 触发执行 20 次/60s/用户；登录 10 次/60s/IP。超限 429 AGT-RATE_LIMIT-001。
 */
@Component
@Order(20)
public class RateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final int triggerMax;
    private final int loginMax;

    public RateLimitFilter(
            StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Value(
                    "${agentflow.rate-limit.trigger-max:20}") int triggerMax,
            @org.springframework.beans.factory.annotation.Value(
                    "${agentflow.rate-limit.login-max:10}") int loginMax) {
        this.redis = redis;
        this.triggerMax = triggerMax;
        this.loginMax = loginMax;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !(path.matches("^/api/v1/workflows/[^/]+/executions$")
                || path.equals("/api/v1/auth/login"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean isLogin = request.getRequestURI().equals("/api/v1/auth/login");
        String subject = isLogin
                ? "ip:" + request.getRemoteAddr()
                : "user:" + userKey(request);
        int max = isLogin ? loginMax : triggerMax;
        long window = System.currentTimeMillis() / 60_000;
        String key = "af:rate:" + (isLogin ? "login" : "trigger") + ":" + window + ":" + subject;
        long count;
        try {
            Long c = redis.opsForValue().increment(key);
            if (c != null && c == 1L) {
                redis.expire(key, java.time.Duration.ofSeconds(120));
            }
            count = c == null ? 0 : c;
        } catch (Exception e) {
            chain.doFilter(request, response);  // 限流器故障 fail-open
            return;
        }
        if (count > max) {
            response.setHeader("Retry-After", "60");
            FilterExceptionWriter.write(response, ErrorCode.AGT_RATE_LIMIT_001,
                    "rate limit exceeded");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String userKey(HttpServletRequest request) {
        var user = request.getAttribute(GatewayAuthFilter.ATTR_USER);
        if (user instanceof AuthenticatedUser u) {
            return u.userId();
        }
        return request.getRemoteAddr();
    }
}
