package com.agentflow.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentflow.common.security.JwtService;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayAuthFilterTest {

    private final JwtService jwt = new JwtService("gateway-test-secret-0123456789abcdef", 900_000);
    private final GatewayAuthFilter filter = new GatewayAuthFilter(jwt, "internal-tok");

    @Test
    void missingBearerIs401() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/workflows");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
        });
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AGT-AUTH-001"));
    }

    @Test
    void validTokenPasses() throws Exception {
        String token = jwt.issue("user-1", List.of("USER"));
        var request = new MockHttpServletRequest("GET", "/api/v1/workflows");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> proceeded((MockHttpServletResponse) res, proceeded));
        assertEquals(200, response.getStatus());
        assertTrue(proceeded.get());
    }

    private void proceeded(MockHttpServletResponse res, AtomicBoolean flag) {
        flag.set(true);
        res.setStatus(200);
    }

    @Test
    void adminPathRequiresAdminRole() throws Exception {
        String token = jwt.issue("user-1", List.of("USER"));
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/usage");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("WF-FORBIDDEN-001"));
    }

    @Test
    void engineRouteRequiresInternalTokenAndHidesItself() throws Exception {
        var request = new MockHttpServletRequest("POST", "/engine/internal/executions");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(404, response.getStatus());  // 不暴露路由存在性

        var ok = new MockHttpServletRequest("POST", "/engine/internal/executions");
        ok.addHeader("X-Internal-Token", "internal-tok");
        var okResp = new MockHttpServletResponse();
        filter.doFilter(ok, okResp, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(200, okResp.getStatus());
    }

    @Test
    void publicPathsSkipped() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(200, response.getStatus());
    }
}
