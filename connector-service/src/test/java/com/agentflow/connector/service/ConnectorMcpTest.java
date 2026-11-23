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
    private final AtomicReference<JsonNode> seenChat = new AtomicReference<>();
    private final java.util.List<JsonNode> replies = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private ConnectorInvoker invoker;
    private ConnectorRepository repo;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            seenChat.set(JSON.readTree(exchange.getRequestBody()));
            if ("sampling-llmfail".equals(mode.get())) {
                respond(exchange, 503, "{\"error\":\"down\"}");
                return;
            }
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"sampled\"}}],"
                    + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2},\"model\":\"stub-model\"}");
        });
        server.createContext("/", exchange -> {
            seenApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            seenSession.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            // 客户端对反向请求的回包（result/error 帧）：记录后 202
            if (request.has("result") || request.has("error")) {
                replies.add(request);
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            String method = request.path("method").asText("");
            int id = request.path("id").asInt(0);
            // 会话过期模式：带 sess-1 的业务请求一律 404，重建后的 sess-2 放行
            if ("expired".equals(mode.get()) && "sess-1".equals(seenSession.get())
                    && (method.equals("tools/call") || method.equals("resources/read")
                        || method.equals("prompts/get"))) {
                respond(exchange, 404, "{\"error\":\"session expired\"}");
                return;
            }
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
                case "resources/list" -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"resources\":[{\"uri\":\"test://doc\",\"name\":\"doc\","
                        + "\"description\":\"说明\",\"mimeType\":\"text/plain\"}]}}");
                case "prompts/list" -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"prompts\":[{\"name\":\"greet\",\"description\":\"问候\","
                        + "\"arguments\":[{\"name\":\"who\",\"description\":\"对象\",\"required\":true}]}]}}");
                case "resources/read" -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"contents\":[{\"uri\":\"" + request.path("params").path("uri").asText()
                        + "\",\"type\":\"text\",\"text\":\"hello resource\"}]}}");
                case "prompts/get" -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\","
                        + "\"text\":\"hi " + request.path("params").path("arguments").path("who").asText()
                        + "\"}}]}}");
                case "tools/call" -> {
                    calls.incrementAndGet();
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
                    if (mode.get().startsWith("sampling")) {
                        // 服务端在 SSE 流里反向请求 sampling，等客户端回包后再给结果
                        StringBuilder sse = new StringBuilder();
                        int requests = "sampling-many".equals(mode.get()) ? 3 : 1;
                        for (int i = 0; i < requests; i++) {
                            sse.append("data: {\"jsonrpc\":\"2.0\",\"id\":").append(900 + i)
                                    .append(",\"method\":\"sampling/createMessage\",\"params\":")
                                    .append("{\"messages\":[{\"role\":\"user\",\"content\":")
                                    .append("{\"type\":\"text\",\"text\":\"think\"}}],")
                                    .append("\"maxTokens\":99999}}\n\n");
                        }
                        sse.append("data: ").append(body).append("\n\n");
                        respondSseRaw(exchange, sse.toString());
                    } else if ("sse".equals(mode.get())) {
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
        respondSseRaw(exchange, "data: " + json + "\n\n");
    }

    /** 已自带 data: 分帧的完整 SSE 流原样写出，不再加前缀。 */
    private static void respondSseRaw(com.sun.net.httpserver.HttpExchange exchange, String sse)
            throws java.io.IOException {
        byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
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

    /** 带 sampling 配置的 MCP 连接：借同租户的 LLM 连接器回答反向请求。 */
    private ConnectorRow samplingConnector(int maxRequests) throws Exception {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, Object> config = Map.of(
                "baseUrl", baseUrl,
                "allowedHosts", List.of("127.0.0.1"),
                "sampling", Map.of("connectorId", "llm-1", "maxTokens", 64,
                        "maxRequests", maxRequests));
        return new ConnectorRow("conn-1", "space-a", "stub-mcp", "MCP", "NONE",
                JSON.valueToTree(config), true, "u-admin");
    }

    private ConnectorRow llmConnector() throws Exception {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, Object> config = Map.of(
                "baseUrl", baseUrl,
                "allowedHosts", List.of("127.0.0.1"),
                "defaultModel", "stub-model",
                "operations", Map.of("chat", Map.of("method", "POST", "path", "/chat/completions")));
        return new ConnectorRow("llm-1", "space-a", "stub-llm", "LLM", "NONE",
                JSON.valueToTree(config), true, "u-admin");
    }

    private ConnectorInvoker.InvokeCommand command(String callId) {
        return new ConnectorInvoker.InvokeCommand("space-a", "conn-1", callId,
                "crm-search", Map.of("q", "acme"), 5_000, "trace-1");
    }

    private ConnectorInvoker.InvokeCommand command(String callId, String method,
                                                   Map<String, Object> params) {
        return new ConnectorInvoker.InvokeCommand("space-a", "conn-1", callId,
                "label", params, 5_000, "trace-1", method);
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

    @Test
    void resourceReadNormalizesTextAndKeepsContents() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-res", "resources/read",
                Map.of("uri", "test://doc")));
        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("text", "hello resource");
        assertThat(result.data()).containsKey("contents");
    }

    @Test
    void promptGetNormalizesTextWithRolePrefix() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-prompt", "prompts/get",
                Map.of("name", "greet", "arguments", Map.of("who", "acme"))));
        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("text", "user: hi acme");
        assertThat(result.data()).containsKey("messages");
    }

    @Test
    void missingResourceUriIsPermanentConfigError() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-res-bad", "resources/read", Map.of()));
        assertThat(result.errorCode()).isEqualTo("CONN-CONFIG-001");
        assertThat(result.transientError()).isFalse();
    }

    @Test
    void unknownMcpMethodIsRejected() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-unknown", "resources/subscribe", Map.of()));
        assertThat(result.errorCode()).isEqualTo("CONN-CONFIG-001");
        assertThat(calls.get()).isZero();
    }

    @Test
    void expiredSessionRebuildAlsoCoversResourceRead() throws Exception {
        mode.set("expired");
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-res-exp", "resources/read",
                Map.of("uri", "test://doc")));
        assertThat(result.ok()).isTrue();
        assertThat(initializes.get()).isEqualTo(2);
    }

    @Test
    void samplingBorrowsTenantLlmAndClampsTokens() throws Exception {
        mode.set("sampling");
        when(repo.findById("conn-1")).thenReturn(Optional.of(samplingConnector(4)));
        when(repo.findById("llm-1")).thenReturn(Optional.of(llmConnector()));
        var result = invoker.invoke(command("call-samp"));
        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsKey("sampling");
        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) result.data().get("sampling");
        assertThat(sampling).containsEntry("requests", 1)
                .containsEntry("promptTokens", 3L).containsEntry("completionTokens", 2L);
        // 服务端要 99999 token，被配置上限 64 钳制；模型取 LLM 连接的 defaultModel
        assertThat(seenChat.get().path("max_tokens").asInt()).isEqualTo(64);
        assertThat(seenChat.get().path("model").asText()).isEqualTo("stub-model");
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).path("result").path("content").path("text").asText())
                .isEqualTo("sampled");
        assertThat(replies.get(0).path("id").asInt()).isEqualTo(900);
    }

    @Test
    void samplingRequestCapRepliesMethodNotFound() throws Exception {
        mode.set("sampling-many");
        when(repo.findById("conn-1")).thenReturn(Optional.of(samplingConnector(2)));
        when(repo.findById("llm-1")).thenReturn(Optional.of(llmConnector()));
        var result = invoker.invoke(command("call-samp-cap"));
        assertThat(result.ok()).isTrue();
        assertThat(replies).hasSize(3);
        assertThat(replies.get(2).path("error").path("code").asInt()).isEqualTo(-32601);
        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) result.data().get("sampling");
        assertThat(sampling).containsEntry("requests", 2);
    }

    @Test
    void samplingUnconfiguredRepliesMethodNotFound() throws Exception {
        mode.set("sampling");
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var result = invoker.invoke(command("call-samp-off"));
        assertThat(result.ok()).isTrue();
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(result.data()).doesNotContainKey("sampling");
    }

    @Test
    void samplingLlmFailureRepliesInternalError() throws Exception {
        mode.set("sampling-llmfail");
        when(repo.findById("conn-1")).thenReturn(Optional.of(samplingConnector(4)));
        when(repo.findById("llm-1")).thenReturn(Optional.of(llmConnector()));
        var result = invoker.invoke(command("call-samp-fail"));
        assertThat(result.ok()).isTrue();
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).path("error").path("code").asInt()).isEqualTo(-32603);
    }

    @Test
    void probeListsResourcesAndPrompts() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE")));
        var resources = invoker.probeMcpResources(connector("NONE"), 5_000);
        assertThat(resources.ok()).isTrue();
        assertThat(resources.resources()).hasSize(1);
        assertThat(resources.resources().get(0).uri()).isEqualTo("test://doc");
        assertThat(resources.resources().get(0).mimeType()).isEqualTo("text/plain");

        var prompts = invoker.probeMcpPrompts(connector("NONE"), 5_000);
        assertThat(prompts.ok()).isTrue();
        assertThat(prompts.prompts()).hasSize(1);
        assertThat(prompts.prompts().get(0).name()).isEqualTo("greet");
        assertThat(prompts.prompts().get(0).arguments()).hasSize(1);
        assertThat(prompts.prompts().get(0).arguments().get(0).required()).isTrue();
    }
}
