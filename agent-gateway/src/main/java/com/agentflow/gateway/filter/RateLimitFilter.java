package com.agentflow.gateway.filter;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AuthenticatedUser;
import com.agentflow.common.web.FilterExceptionWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 粗粒度限流（Redis 固定窗口）：触发执行 20 次/60s/用户；登录 10 次/60s/IP。
 * 超限 429 AGT-RATE_LIMIT-001 并带 {@code Retry-After}。
 *
 * <p>计数主体：登录按来源地址，触发按已认证用户。当前部署不假设存在反向代理
 * （未启用 forward-headers 策略），因此直接取 {@code getRemoteAddr()}；若日后置于
 * 代理之后，需先启用 forward-headers 再改此处，否则所有用户会共享代理地址的额度。
 */
@Component
@Order(20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    /**
     * 计数与过期必须原子完成：分两次调用时若 increment 成功而 expire 失败，
     * key 会永久驻留，逐窗口累积成内存泄漏。
     */
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('incr', KEYS[1]) "
                    + "if c == 1 then redis.call('expire', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final int triggerMax;
    private final int loginMax;
    /** 固定窗口宽度：生产由配置注入，与 {@code Retry-After} 保持一致；单测默认 60。 */
    @Value("${agentflow.rate-limit.trigger-window-seconds:60}")
    private long triggerWindowSeconds = 60;
    @Value("${agentflow.rate-limit.login-window-seconds:60}")
    private long loginWindowSeconds = 60;

    public RateLimitFilter(
            StringRedisTemplate redis,
            @Value("${agentflow.rate-limit.trigger-max:20}") int triggerMax,
            @Value("${agentflow.rate-limit.login-max:10}") int loginMax) {
        this.redis = redis;
        this.triggerMax = triggerMax;
        this.loginMax = loginMax;
        log.info("rate limit: trigger={} per user, login={} per ip (window from config)",
                triggerMax, loginMax);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = normalise(request.getRequestURI());
        return !(path.matches("^/api/v1/workflows/[^/]+/executions$") || LOGIN_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean isLogin = LOGIN_PATH.equals(normalise(request.getRequestURI()));
        String bucket = isLogin ? "login" : "trigger";
        String subject = isLogin
                ? "ip:" + request.getRemoteAddr()
                : "user:" + userKey(request);
        int max = isLogin ? loginMax : triggerMax;
        long windowSeconds = isLogin ? loginWindowSeconds : triggerWindowSeconds;
        long window = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = "af:rate:" + bucket + ":" + window + ":" + subject;
        // key 多留一个窗口，避免跨窗口边界时旧计数被立即回收
        long keyTtlSeconds = windowSeconds * 2;

        long count;
        try {
            Long c = redis.execute(INCREMENT_SCRIPT, List.of(key), String.valueOf(keyTtlSeconds));
            count = c == null ? 0L : c;
        } catch (RuntimeException e) {
            // 限流依赖 Redis：故障时放行优先于误伤正常请求，但必须留痕，
            // 否则表现为「限流静默失效」，只能等出事才发现。
            log.warn("rate limit unavailable, passing through: bucket={} subject={} reason={}",
                    bucket, subject, e.toString());
            chain.doFilter(request, response);
            return;
        }

        if (count > max) {
            // 只在首次越界的那个请求上留痕：持续刷接口的客户端每个窗口只产生一条日志，
            // 避免告警洪泛本身变成写放大。
            if (count == max + 1L) {
                log.warn("rate limit exceeded: bucket={} subject={} max={} window={}s"
                                + " further rejections in this window are not logged",
                        bucket, subject, max, windowSeconds);
            }
            response.setHeader("Retry-After", String.valueOf(windowSeconds));
            FilterExceptionWriter.write(response, ErrorCode.AGT_RATE_LIMIT_001,
                    "rate limit exceeded: " + max + " requests per " + windowSeconds + "s");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 去掉末尾多余斜杠，避免 {@code /executions/} 这类变体绕过限流匹配。 */
    private static String normalise(String uri) {
        int end = uri.length();
        while (end > 1 && uri.charAt(end - 1) == '/') {
            end--;
        }
        return uri.substring(0, end);
    }

    private static String userKey(HttpServletRequest request) {
        var user = request.getAttribute(GatewayAuthFilter.ATTR_USER);
        if (user instanceof AuthenticatedUser u) {
            return u.userId();
        }
        return request.getRemoteAddr();
    }
}
