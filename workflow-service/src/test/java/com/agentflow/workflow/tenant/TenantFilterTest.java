package com.agentflow.workflow.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.agentflow.common.tenant.TenantContext;
import com.agentflow.workflow.security.JwtAuthFilter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantFilterTest {

    private final TenantMapper tenantMapper = Mockito.mock(TenantMapper.class);
    private final TenantFilter filter =
            new TenantFilter(new MybatisTenantTreePort(tenantMapper));

    private static MockHttpServletRequest request(String spaceId) {
        var request = new MockHttpServletRequest("GET", "/api/v1/workflows");
        request.setAttribute(JwtAuthFilter.ATTR_USER_ID, "user-1");
        if (spaceId != null) {
            request.addHeader(TenantFilter.HEADER_SPACE, spaceId);
        }
        return request;
    }

    @Test
    void missingSpaceHeaderIs404() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(null), response, (req, res) -> {
        });
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("WF-NOT_FOUND-001"));
    }

    @Test
    void nonMemberIs404() throws Exception {
        when(tenantMapper.findNode("space-x"))
                .thenReturn(new TenantMapper.TenantNodeRow("space-x", null, "SPACE"));
        var response = new MockHttpServletResponse();
        filter.doFilter(request("space-x"), response, (req, res) -> {
        });
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatus());
    }

    @Test
    void memberGetsTenantContext() throws Exception {
        when(tenantMapper.findNode("space-1"))
                .thenReturn(new TenantMapper.TenantNodeRow("space-1", "org-1", "SPACE"));
        when(tenantMapper.findMemberRole("space-1", "user-1")).thenReturn("EDITOR");
        AtomicReference<String> seenRole = new AtomicReference<>();
        var response = new MockHttpServletResponse();
        filter.doFilter(request("space-1"), response, (req, res) ->
                seenRole.set(TenantContext.require().role()));
        assertEquals("EDITOR", seenRole.get());
        assertEquals(200, response.getStatus());
        assertTrue(TenantContext.get() == null); // 过滤器退出后必须清理
    }

    @Test
    void orgMemberInheritsIntoChildSpace() throws Exception {
        when(tenantMapper.findNode("space-1"))
                .thenReturn(new TenantMapper.TenantNodeRow("space-1", "team-1", "SPACE"));
        when(tenantMapper.findNode("team-1"))
                .thenReturn(new TenantMapper.TenantNodeRow("team-1", "org-1", "TEAM"));
        when(tenantMapper.findNode("org-1"))
                .thenReturn(new TenantMapper.TenantNodeRow("org-1", null, "ORG"));
        when(tenantMapper.findMemberRole("org-1", "user-1")).thenReturn("OWNER");
        AtomicReference<TenantContext.TenantInfo> seen = new AtomicReference<>();
        var response = new MockHttpServletResponse();
        filter.doFilter(request("space-1"), response, (req, res) ->
                seen.set(TenantContext.require()));
        assertEquals("ADMIN", seen.get().role()); // OWNER 在子空间降级为 ADMIN
        assertEquals("org-1", seen.get().inheritedFrom());
        assertTrue(seen.get().inherited());
    }
}
