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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 连接器调用：成功/4xx/5xx/凭据注入/幂等缓存。 */
class ConnectorInvokerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "ZGV2LW9ubHktY3JlZC1tYXN0ZXIta2V5LTMyYnl0ZXM=";

    private HttpServer server;
    private final AtomicReference<String> seenApiKey = new AtomicReference<>();
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private ConnectorInvoker invoker;
    private ConnectorRepository repo;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            seenApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] body = "{\"answer\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/boom", exchange -> {
            byte[] body = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/bad", exchange -> {
            byte[] body = "{\"error\":\"missing\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        repo = mock(ConnectorRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> cache.get(i.getArgument(0, String.class)));
        // 幂等锁：单测串行执行，始终抢到锁
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doAnswer(i -> {
            cache.put(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));

        invoker = new ConnectorInvoker(repo, new CredentialCipher(KEY), redis,
                new SandboxPolicy(true, true));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ConnectorRow connector(String authType, String operationPath) throws Exception {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, Object> config = Map.of(
                "baseUrl", baseUrl,
                "allowedHosts", java.util.List.of("127.0.0.1"),
                "operations", Map.of("run", Map.of("method", "POST", "path", operationPath)));
        return new ConnectorRow("conn-1", "space-a", "demo", "HTTP", authType,
                JSON.valueToTree(config), true, "u-admin");
    }

    @Test
    void invokesAndParsesJson() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE", "/ok")));
        var result = invoker.invoke(new ConnectorInvoker.InvokeCommand("space-a", "conn-1",
                "call-1", "run", Map.of(), 5_000, "trace-1"));
        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("answer", 42);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void injectsApiKeyHeaderFromEncryptedCredential() throws Exception {
        CredentialCipher cipher = new CredentialCipher(KEY);
        byte[] sealed = cipher.seal("top-secret".getBytes(StandardCharsets.UTF_8),
                CredentialCipher.aad("conn-1", "space-a"));
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("API_KEY", "/ok")));
        when(repo.findCredential("conn-1")).thenReturn(Optional.of(new CredentialRow(
                "conn-1", "space-a", sealed, null, cipher.keyId(), null, null)));

        var result = invoker.invoke(new ConnectorInvoker.InvokeCommand("space-a", "conn-1",
                "call-2", "run", Map.of("q", "x"), 5_000, "trace-2"));

        assertThat(result.ok()).isTrue();
        assertThat(seenApiKey.get()).isEqualTo("top-secret");
    }

    @Test
    void missingCredentialIsPermanentFailure() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("API_KEY", "/ok")));
        when(repo.findCredential("conn-1")).thenReturn(Optional.empty());
        var result = invoker.invoke(new ConnectorInvoker.InvokeCommand("space-a", "conn-1",
                "call-3", "run", Map.of(), 5_000, "trace-3"));
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("CONN-CREDENTIAL-001");
        assertThat(result.transientError()).isFalse();
    }

    @Test
    void serverErrorIsTransient() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE", "/boom")));
        var result = invoker.invoke(new ConnectorInvoker.InvokeCommand("space-a", "conn-1",
                "call-4", "run", Map.of(), 5_000, "trace-4"));
        assertThat(result.errorCode()).isEqualTo("CONN-HTTP-5XX");
        assertThat(result.transientError()).isTrue();
    }

    @Test
    void clientErrorIsPermanent() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE", "/bad")));
        var result = invoker.invoke(new ConnectorInvoker.InvokeCommand("space-a", "conn-1",
                "call-5", "run", Map.of(), 5_000, "trace-5"));
        assertThat(result.errorCode()).isEqualTo("CONN-HTTP-4XX");
        assertThat(result.transientError()).isFalse();
    }

    @Test
    void replayedCallIdReturnsCachedResultWithoutSecondHit() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("API_KEY", "/ok")));
        CredentialCipher cipher = new CredentialCipher(KEY);
        when(repo.findCredential("conn-1")).thenReturn(Optional.of(new CredentialRow(
                "conn-1", "space-a",
                cipher.seal("k".getBytes(StandardCharsets.UTF_8),
                        CredentialCipher.aad("conn-1", "space-a")),
                null, cipher.keyId(), null, null)));

        var first = invoker.invoke(new ConnectorInvoker.InvokeCommand("space-a", "conn-1",
                "call-6", "run", Map.of(), 5_000, "t"));
        seenApiKey.set(null);
        var second = invoker.invoke(new ConnectorInvoker.InvokeCommand("space-a", "conn-1",
                "call-6", "run", Map.of(), 5_000, "t"));

        assertThat(first.ok()).isTrue();
        assertThat(second.data()).containsEntry("_replayed", true);
        assertThat(seenApiKey.get()).isNull();   // 没有第二次出网
    }

    @Test
    void inFlightCallIdIsRejectedWithoutSecondHit() throws Exception {
        when(repo.findById("conn-1")).thenReturn(Optional.of(connector("NONE", "/ok")));
        // 精确覆盖该 callId 的锁为"已被并发持有"
        when(ops.setIfAbsent(org.mockito.ArgumentMatchers.eq("af:conncall:call-lock:lock"),
                anyString(), any(Duration.class))).thenReturn(false);

        var result = invoker.invoke(new ConnectorInvoker.InvokeCommand("space-a", "conn-1",
                "call-lock", "run", Map.of(), 5_000, "t"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("CONN-INFLIGHT");
        assertThat(result.transientError()).isTrue();
    }

    @Test
    void unknownConnectorIsNotFound() {
        when(repo.findById("missing")).thenReturn(Optional.empty());
        var result = invoker.invoke(new ConnectorInvoker.InvokeCommand("space-a", "missing",
                "call-7", "run", Map.of(), 5_000, "t"));
        assertThat(result.errorCode()).isEqualTo("CONN-NOT_FOUND-001");
    }
}
