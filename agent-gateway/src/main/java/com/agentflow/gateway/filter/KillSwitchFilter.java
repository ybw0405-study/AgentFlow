package com.agentflow.gateway.filter;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.web.FilterExceptionWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Kill Switch（US-05）：Redis 开关 + 30s 本地缓存 + Redis 故障 fail-open。
 * 生效时执行类请求 503 AGT-UPSTREAM-003；非 AI 功能不受影响。
 */
@Component
@Order(25)
public class KillSwitchFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchFilter.class);

    private final StringRedisTemplate redis;
    private final String key;
    private final long cacheTtlMs;
    private final AtomicBoolean cached = new AtomicBoolean(false);
    private final AtomicLong cachedAt = new AtomicLong(0);

    public KillSwitchFilter(StringRedisTemplate redis,
                            @Value("${agentflow.kill-switch.redis-key}") String redisKey,
                            @Value("${agentflow.kill-switch.cache-ttl-ms}") long cacheTtlMs) {
        this.redis = redis;
        this.key = redisKey;
        this.cacheTtlMs = cacheTtlMs;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        // 只拦截 AI 副作用入口：触发执行 + 引擎派发路由
        return !(path.matches("^/api/v1/workflows/[^/]+/executions$")
                || path.matches("^/engine/internal/executions$"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isActive()) {
            FilterExceptionWriter.write(response, ErrorCode.AGT_UPSTREAM_003,
                    "AI capability disabled by administrator");
            return;
        }
        chain.doFilter(request, response);
    }

    boolean isActive() {
        long now = System.currentTimeMillis();
        if (now - cachedAt.get() < cacheTtlMs) {
            return cached.get();
        }
        try {
            String v = redis.opsForValue().get(key);
            cached.set("1".equals(v) || "true".equalsIgnoreCase(v != null ? v : ""));
        } catch (Exception e) {
            log.warn("kill switch read failed, fail-open: {}", e.getMessage());
            // fail-open：缓存值保持，不影响非 AI 功能
        }
        cachedAt.set(now);
        return cached.get();
    }
}
