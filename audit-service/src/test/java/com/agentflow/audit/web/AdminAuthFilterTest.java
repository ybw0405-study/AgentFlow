package com.agentflow.audit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentflow.common.security.JwtService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAuthFilterTest {

    private final JwtService jwt = new JwtService("audit-test-secret-0123456789abcdef", 900_000);
    private final AdminAuthFilter filter = new AdminAuthFilter(jwt);

    @Test
    void nonAdminIs403() throws Exception {
        String token = jwt.issue("user-1", List.of("USER"));
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/audit");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(403, response.getStatus());
    }

    @Test
    void adminPasses() throws Exception {
        String token = jwt.issue("admin-1", List.of("USER", "ADMIN"));
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/audit");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(200, response.getStatus());
    }

    @Test
    void noTokenIs401() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/audit");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AGT-AUTH-001"));
    }
}
