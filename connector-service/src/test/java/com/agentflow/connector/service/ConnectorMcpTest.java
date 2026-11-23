package com.agentflow.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentflow.connector.crypto.CredentialCipher;
import com.agentflow.connector.domain.ConnectorRepository;
import com.agentflow.connector.domain.ConnectorRepository.ConnectorRow;
import com.agentflow.connector.domain.ConnectorRepository.CredentialRow;
import com.agentflow.connector.tenant.SelfTenantTreePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** MCP 连接器：JSON/SSE 双响应、会话过期重建、工具级错误、凭据注入、沙箱与幂等重放。 */
class ConnectorMcpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "ZGV2LW9ubHktY3JlZC1tYXN0ZXIta2V5LTMyYnl0ZXM=";

    private HttpServer server;
    private final AtomicReference<String> mode = new AtomicReference<>("json");
    private final AtomicReference<String> seenApiKey = new AtomicReference<>();
    private final AtomicReference<String> seenSession = new AtomicReference<>();
    private final AtomicInteger initializes = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private ConnectorInvoker invoker;
    private ConnectorRepository repo;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            seenSession.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String method = request.path("method").asText("");
            int id = request.path("id").asInt(0);
            switch (method) {
                case "initialize" -> {
                    initializes.incrementAndGet();
                    String session = "sess-" + initializes.get();
                    exchange.getResponseHeaders().add("Mcp-Session-Id", session);
                    String version = "badversion".equals(mode.get())
                            ? "1999-01-01" : McpProtocol.PREFERRED_VERSION;
                    respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"result\":{\"protocolVersion\":\"" + version
                            + "\",\"capabilities\":{},\"serverInfo\":{\"name\":\"stub\",\"version\":\"0\"}}}");
                }
                case "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                }
                case "tools/list" -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"tools\":[{\"name\":\"crm-search\",\"description\":\"查客户\","
                        + "\"inputSchema\":{\"type\":\"object\"}}]}}");
                case "tools/call" -> {
                    calls.incrementAndGet();
                    // 会话过期模式：第一次调用（sess-1）返回 404，重建后的 sess-2 放行
                    if ("expired".equals(mode.get()) && "sess-1".equals(seenSession.get())) {
                        respond(exchange, 404, "{\"error\":\"session expired\"}");
                        return;
                    }
                    if ("toolerror".equals(mode.get())) {
                        respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                                + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"denied\"}],"
                                + "\"isError\":true}}");
                        return;
                    }
                    if ("rpcerror".equals(mode.get())) {
                        respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                                + ",\"error\":{\"code\":-32602,\"message\":\"bad params\"}}");
                        return;
                    }
                    String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"pong\"}],"
                            + "\"isError\":false}}";
                    if ("sse".equals(mode.get())) {
                        respondSse(exchange, body);
                    } else {
                        respond(exchange, 200, body);
                    }
                }
                default -> respond(exchange, 400, "{\"error\":\"unknown method\"}");
            }
        });
        server.start();

        repo = mock(ConnectorRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> cache.get(i.getArgument(0, String.class)));
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doAnswer(i -> {
            cache.put(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));

        invoker = new ConnectorInvoker(repo, new CredentialCipher(KEY), redis,
                new SandboxPolicy(true, true), new SelfTenantTreePort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respondSse(com.sun.net.httpserver.HttpExchange exchange, String json)
            throws java.io.IOException {
        byte[] bytes = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private ConnectorRow connector(String authType) throws Exception {
        return connector(authType, List.of("127.0.0.1"));
    }

    private ConnectorRow connector(String authType, List<String> allowedHosts) throws Exception {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, Object> config = Map.of("baseUrl", baseUrl, "allowedHosts", allowedHosts);
        return new ConnectorRow("conn-1", "space-a", "stub-mcp", "MCP", authType,
                JSON.valueToTree(config), true, "u-admin");
    }

    private ConnectorInvoker.InvokeCommand command(String callId) {
        return new ConnectorInvoker.InvokeCommand("space-a", "conn-1", callId,
                "crm-search", Map.of("q", "acme"), 5_000, "trace-1");
    }

    @Test
    void invokesToolOverJsonAndCachesResult() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-1"));
        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("isError", false);
        assertThat(initializes.get()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void invokesToolOverSseResponse() throws Exception {
        mode.set("sse");
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-sse"));
        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("isError", false);
    }

    @Test
    void expiredSessionIsRebuiltOnceTransparently() throws Exception {
        mode.set("expired");
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-exp"));
        assertThat(result.ok()).isTrue();
        assertThat(initializes.get()).isEqualTo(2);
        assertThat(seenSession.get()).isEqualTo("sess-2");
    }

    @Test
    void toolLevelErrorIsPermanentAndKeepsContent() throws Exception {
        mode.set("toolerror");
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-err"));
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("CONN-TOOL-001");
        assertThat(result.transientError()).isFalse();
        assertThat(result.data()).containsEntry("isError", true);
    }

    @Test
    void jsonRpcInvalidParamsIsPermanent() throws Exception {
        mode.set("rpcerror");
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-rpc"));
        assertThat(result.errorCode()).isEqualTo("CONN-MCP-002");
        assertThat(result.transientError()).isFalse();
    }

    @Test
    void unsupportedProtocolVersionIsRejected() throws Exception {
        mode.set("badversion");
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-ver"));
        assertThat(result.errorCode()).isEqualTo("CONN-MCP-001");
        assertThat(result.transientError()).isFalse();
    }

    @Test
    void injectsApiKeyHeaderFromEncryptedCredential() throws Exception {
        CredentialCipher cipher = new CredentialCipher(KEY);
        byte[] sealed = cipher.seal("mcp-secret".getBytes(StandardCharsets.UTF_8),
                CredentialCipher.aad("conn-1", "space-a"));
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("API_KEY")));
        when(repo.findCredential("conn-1")).thenReturn(Optional.of(new CredentialRow(
                "conn-1", "space-a", sealed, null, cipher.keyId(), null, null)));
        var result = invoker.invoke(command("call-auth"));
        assertThat(result.ok()).isTrue();
        assertThat(seenApiKey.get()).isEqualTo("mcp-secret");
    }

    @Test
    void sandboxRejectionIsPermanent() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE", List.of("other.example"))));
        var result = invoker.invoke(command("call-sandbox"));
        assertThat(result.errorCode()).isEqualTo("CONN-SANDBOX-001");
        assertThat(result.transientError()).isFalse();
        assertThat(calls.get()).isZero();
    }

    @Test
    void replayedCallIdSkipsSecondHandshake() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var first = invoker.invoke(command("call-replay"));
        int hits = initializes.get();
        var second = invoker.invoke(command("call-replay"));
        assertThat(first.ok()).isTrue();
        assertThat(second.data()).containsEntry("_replayed", true);
        assertThat(initializes.get()).isEqualTo(hits);
    }

    @Test
    void probeListsToolsWithSchema() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.probeMcpTools(connector("NONE"), 5_000);
        assertThat(result.ok()).isTrue();
        assertThat(result.tools()).hasSize(1);
        assertThat(result.tools().get(0).name()).isEqualTo("crm-search");
        assertThat(result.tools().get(0).inputSchema()).containsEntry("type", "object");
    }
}
