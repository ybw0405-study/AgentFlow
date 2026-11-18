package com.agentflow.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private final StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);

    /**
     * 模拟 Lua 脚本的 INCR 语义：同一组 key 连续调用返回递增计数。
     * 用真实递增而非固定值，才能验证「第 max 次通过、第 max+1 次拒绝」的边界。
     */
    private void stubRealCounters() {
        var counter = new AtomicLong();
        when(redis.execute(any(), anyList(), any())).thenAnswer(inv -> counter.incrementAndGet());
    }

    private static MockHttpServletResponse run(RateLimitFilter filter, String method, String uri)
            throws Exception {
        var request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("10.0.0.7");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        return response;
    }

    @Test
    void allowsUpToLimitThenRejectsNext() throws Exception {
        stubRealCounters();
        var filter = new RateLimitFilter(redis, 3, 10);

        for (int i = 0; i < 3; i++) {
            assertEquals(200, run(filter, "POST", "/api/v1/workflows/w1/executions").getStatus(),
                    "request #" + (i + 1) + " should pass");
        }

        var rejected = run(filter, "POST", "/api/v1/workflows/w1/executions");
        assertEquals(429, rejected.getStatus());
        assertEquals("60", rejected.getHeader("Retry-After"));
        assertTrue(rejected.getContentAsString().contains("AGT-RATE_LIMIT-001"));
        assertTrue(rejected.getContentAsString().contains("3 requests per 60s"));
    }

    @Test
    void staysRejectedForEveryFurtherRequestInWindow() throws Exception {
        stubRealCounters();
        var filter = new RateLimitFilter(redis, 1, 10);

        assertEquals(200, run(filter, "POST", "/api/v1/workflows/w1/executions").getStatus());
        for (int i = 0; i < 3; i++) {
            assertEquals(429, run(filter, "POST", "/api/v1/workflows/w1/executions").getStatus());
        }
    }

    @Test
    void trailingSlashDoesNotBypassLimit() throws Exception {
        stubRealCounters();
        var filter = new RateLimitFilter(redis, 1, 10);

        assertEquals(200, run(filter, "POST", "/api/v1/workflows/w1/executions").getStatus());
        assertEquals(429, run(filter, "POST", "/api/v1/workflows/w1/executions/").getStatus());
    }

    @Test
    void loginUsesItsOwnStricterLimit() throws Exception {
        stubRealCounters();
        var filter = new RateLimitFilter(redis, 20, 2);

        assertEquals(200, run(filter, "POST", "/api/v1/auth/login").getStatus());
        assertEquals(200, run(filter, "POST", "/api/v1/auth/login").getStatus());
        assertEquals(429, run(filter, "POST", "/api/v1/auth/login").getStatus());
    }

    @Test
    void getRequestsAreNeverLimited() throws Exception {
        stubRealCounters();
        var filter = new RateLimitFilter(redis, 0, 0);
        assertEquals(200, run(filter, "GET", "/api/v1/workflows").getStatus());
    }

    @Test
    void unrelatedPostPathsAreNeverLimited() throws Exception {
        stubRealCounters();
        var filter = new RateLimitFilter(redis, 0, 0);
        assertEquals(200, run(filter, "POST", "/api/v1/connectors").getStatus());
    }

    @Test
    void nullCountFromRedisIsTreatedAsZero() throws Exception {
        when(redis.execute(any(), anyList(), any())).thenReturn(null);
        var filter = new RateLimitFilter(redis, 1, 1);
        assertEquals(200, run(filter, "POST", "/api/v1/workflows/w1/executions").getStatus());
    }

    @Test
    void redisFailurePassesThroughWithoutLimitHeaders() throws Exception {
        when(redis.execute(any(), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        var filter = new RateLimitFilter(redis, 1, 1);
        var response = run(filter, "POST", "/api/v1/workflows/w1/executions");
        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Retry-After"));
    }
}
