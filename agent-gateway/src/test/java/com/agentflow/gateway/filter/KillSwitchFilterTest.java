package com.agentflow.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class KillSwitchFilterTest {

    private final StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
    private KillSwitchFilter filter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        filter = new KillSwitchFilter(redis, "af:kill-switch", 30_000);
    }

    @Test
    void activeSwitchBlocksTriggerWith503() throws Exception {
        when(ops.get("af:kill-switch")).thenReturn("1");
        var request = new MockHttpServletRequest("POST", "/api/v1/workflows/w1/executions");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("AGT-UPSTREAM-003"));
    }

    @Test
    void inactiveSwitchPasses() throws Exception {
        when(ops.get("af:kill-switch")).thenReturn(null);
        var request = new MockHttpServletRequest("POST", "/api/v1/workflows/w1/executions");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(200, response.getStatus());
    }

    @Test
    void nonAiPathsNeverBlocked() throws Exception {
        when(ops.get("af:kill-switch")).thenReturn("1");
        var request = new MockHttpServletRequest("GET", "/api/v1/workflows");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(200, response.getStatus());
    }

    @Test
    void redisFailureFailsOpen() throws Exception {
        when(ops.get("af:kill-switch")).thenThrow(new RuntimeException("redis down"));
        var request = new MockHttpServletRequest("POST", "/api/v1/workflows/w1/executions");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(200, response.getStatus());
    }
}
