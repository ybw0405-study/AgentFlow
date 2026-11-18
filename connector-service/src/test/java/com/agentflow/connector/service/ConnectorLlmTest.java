package com.agentflow.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentflow.connector.crypto.CredentialCipher;
import com.agentflow.connector.domain.ConnectorRepository;
import com.agentflow.connector.domain.ConnectorRepository.ConnectorRow;
import com.agentflow.connector.domain.ConnectorRepository.CredentialRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 模型连接：Bearer 凭据形态与模型清单探测。
 *
 * <p>这两条是 LLM 与普通 HTTP 连接器的差异点，各自都有明确的出错方式：
 * 少了 Bearer 前缀上游会 401，模型清单解析放宽会把上游垃圾当成模型名。
 */
class ConnectorLlmTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "ZGV2LW9ubHktY3JlZC1tYXN0ZXIta2V5LTMyYnl0ZXM=";

    private HttpServer server;
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private ConnectorInvoker invoker;
    private ConnectorRepository repo;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"object\":\"list\",\"data\":["
                    + "{\"id\":\"deepseek-reasoner\",\"owned_by\":\"x\"},"
                    + "{\"id\":\"deepseek-chat\"},"
                    + "{\"id\":\"deepseek-chat\"},"
                    + "{\"id\":\"\"},"
                    + "{\"noid\":true}"
                    + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/denied/v1/models", exchange -> {
            byte[] body = "{\"error\":\"bad key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/legacy/models", exchange -> {
            byte[] body = "[\"model-a\",\"model-b\"]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        repo = mock(ConnectorRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> null);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doAnswer(i -> null).when(ops).set(anyString(), anyString(), any(Duration.class));

        invoker = new ConnectorInvoker(repo, new CredentialCipher(KEY), redis,
                new SandboxPolicy(true, true));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ConnectorRow llm(String pathPrefix) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + pathPrefix;
        Map<String, Object> config = Map.of(
                "baseUrl", baseUrl,
                "allowedHosts", List.of("127.0.0.1"),
                "auth", Map.of("headerName", "Authorization", "valuePrefix", "Bearer "),
                "operations", Map.of(
                        "chat", Map.of("method", "POST", "path", "/chat/completions"),
                        "models", Map.of("method", "GET", "path", "/models")));
        return new ConnectorRow("conn-llm", "space-a", "deepseek", "LLM", "API_KEY",
                JSON.valueToTree(config), true, "u-admin");
    }

    private void withCredential(String secret) {
        CredentialCipher cipher = new CredentialCipher(KEY);
        when(repo.findCredential("conn-llm")).thenReturn(Optional.of(new CredentialRow(
                "conn-llm", "space-a",
                cipher.seal(secret.getBytes(StandardCharsets.UTF_8),
                        CredentialCipher.aad("conn-llm", "space-a")),
                null, cipher.keyId(), null, null)));
    }

    @Test
    void probeReturnsSortedUniqueModelIdsAndSendsBearerToken() {
        withCredential("sk-test-123");

        ConnectorInvoker.ProbeResult result = invoker.probeModels(llm("/v1"), 5_000);

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("deepseek-chat", "deepseek-reasoner");
        assertThat(seenAuth.get()).isEqualTo("Bearer sk-test-123");
    }

    @Test
    void probeReportsUpstreamRejectionWithoutLeakingBody() {
        withCredential("sk-test-123");

        ConnectorInvoker.ProbeResult result = invoker.probeModels(llm("/denied/v1"), 5_000);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("CONN-HTTP-4XX");
        assertThat(result.models()).isEmpty();
    }

    @Test
    void probeRejectsNonOpenAiShape() {
        withCredential("sk-test-123");

        ConnectorInvoker.ProbeResult result = invoker.probeModels(llm("/legacy"), 5_000);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("CONN-RESPONSE-001");
    }

    @Test
    void probeWithoutCredentialIsPermanent() {
        when(repo.findCredential("conn-llm")).thenReturn(Optional.empty());

        ConnectorInvoker.ProbeResult result = invoker.probeModels(llm("/v1"), 5_000);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("CONN-CREDENTIAL-001");
    }

    @Test
    void authValueAppendsConfiguredPrefix() {
        var config = JSON.valueToTree(Map.of("auth", Map.of("valuePrefix", "Bearer ")));

        assertThat(ConnectorInvoker.authValue(config, "sk-1")).isEqualTo("Bearer sk-1");
    }

    @Test
    void authValueDefaultsToRawSecret() {
        var config = JSON.valueToTree(Map.of("auth", Map.of("headerName", "X-API-Key")));

        assertThat(ConnectorInvoker.authValue(config, "sk-1")).isEqualTo("sk-1");
    }

    @Test
    void authValueRejectsControlCharactersInPrefix() {
        var config = JSON.valueToTree(Map.of("auth", Map.of("valuePrefix", "Bearer\r\nX-Evil: 1")));

        assertThatThrownBy(() -> ConnectorInvoker.authValue(config, "sk-1"))
                .isInstanceOf(ConnectorInvoker.CredentialException.class);
    }

    @Test
    void modelIdsToleratesMissingDataArray() {
        assertThat(ConnectorInvoker.modelIds(Map.of())).isEmpty();
        assertThat(ConnectorInvoker.modelIds(Map.of("data", "not-a-list"))).isEmpty();
    }
}
