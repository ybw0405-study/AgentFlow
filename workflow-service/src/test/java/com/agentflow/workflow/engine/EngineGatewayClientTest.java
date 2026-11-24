package com.agentflow.workflow.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** EngineGatewayClient 必须把 spaceId 下推到 /internal/events 的查询串。 */
class EngineGatewayClientTest {

    @Test
    void eventsUriTemplatePushesSpaceIdOnlyWhenPresent() {
        assertEquals("/events?after_seq={a}&limit={l}&spaceId={s}",
                EngineGatewayClient.eventsUri("space-a"));
        assertEquals("/events?after_seq={a}&limit={l}",
                EngineGatewayClient.eventsUri(null));
        assertEquals("/events?after_seq={a}&limit={l}",
                EngineGatewayClient.eventsUri("   "));
    }

    @Test
    void tailEventsSendsSpaceIdOnTheWireAndOmitsItForGlobalProjector() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/engine/internal/events", exchange -> {
            captured.set(exchange.getRequestURI().toString());
            byte[] body = "{\"events\":[],\"lastSeq\":0}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            EngineGatewayClient client =
                    new EngineGatewayClient(base, "tok", RestClient.builder());

            EngineGatewayClient.EventsPage scoped = client.tailEvents(0, 10, "space-a");
            assertEquals(0L, scoped.lastSeq());
            assertTrue(captured.get().contains("after_seq=0"));
            assertTrue(captured.get().contains("limit=10"));
            assertTrue(captured.get().contains("spaceId=space-a"));

            client.tailEvents(0, 10, null);
            assertFalse(captured.get().contains("spaceId"));
        } finally {
            server.stop(0);
        }
    }
}
