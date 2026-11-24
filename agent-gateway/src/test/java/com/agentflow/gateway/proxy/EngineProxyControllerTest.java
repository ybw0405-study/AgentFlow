package com.agentflow.gateway.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/** 客户端凭据必须被剥离，引擎令牌只能由服务端注入一份。 */
class EngineProxyControllerTest {

    @Test
    void stripsClientCredentialsAndInjectsExactlyOneServerToken() {
        var request = new MockHttpServletRequest("POST", "/engine/internal/executions");
        request.addHeader("X-Internal-Token", "client-token");
        request.addHeader("Authorization", "Bearer user-jwt");
        request.addHeader("Cookie", "refresh=secret");
        request.addHeader("X-Api-Key", "client-key");
        request.addHeader("Connection", "keep-alive");
        request.addHeader("traceparent", "00-trace");

        HttpHeaders out = EngineProxyController.buildOutboundHeaders(request, "server-engine-token");

        assertEquals("server-engine-token", out.getFirst("X-Internal-Token"));
        assertEquals(1, out.get("X-Internal-Token").size());
        assertNull(out.getFirst("Authorization"));
        assertNull(out.getFirst("Cookie"));
        assertNull(out.getFirst("X-Api-Key"));
        assertNull(out.getFirst("Connection"));
        assertEquals("00-trace", out.getFirst("traceparent"));
    }

    @Test
    void doesNotForwardHopByHopOrCaseVariantCredentialHeaders() {
        var request = new MockHttpServletRequest("GET", "/engine/internal/events");
        request.addHeader("x-internal-token", "client-token");
        request.addHeader("Content-Length", "0");
        request.addHeader("Host", "gateway.internal");

        HttpHeaders out = EngineProxyController.buildOutboundHeaders(request, "engine-only");

        assertEquals("engine-only", out.getFirst("X-Internal-Token"));
        assertEquals(1, out.get("X-Internal-Token").size());
        assertNull(out.getFirst("Content-Length"));
        assertNull(out.getFirst("Host"));
    }
}
