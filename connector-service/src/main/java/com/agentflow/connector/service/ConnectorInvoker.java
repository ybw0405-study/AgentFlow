package com.agentflow.connector.service;

import com.agentflow.connector.crypto.CredentialCipher;
import com.agentflow.connector.domain.ConnectorRepository;
import com.agentflow.connector.domain.ConnectorRepository.ConnectorRow;
import com.agentflow.connector.domain.ConnectorRepository.CredentialRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 连接器调用：解析配置 → 解密凭据 → 沙箱校验 → 出网 → 响应裁剪。
 *
 * <p>凭据只在本进程内存中以明文存在，注入 header 后即丢弃；日志与返回体都不含凭据。
 * {@code callId} 作为幂等键写 Redis（24h），崩溃重发同一 callId 返回首次结果。
 */
@Service
public class ConnectorInvoker {

    private static final Logger log = LoggerFactory.getLogger(ConnectorInvoker.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    /** 幂等执行锁 TTL：足够覆盖一次出网，防同 callId 并发双出网（TOCTOU）。 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    /** 释放锁只在持有者是自己时才删——否则 TTL 过期后被别人拿到，会误删他人锁。 */
    private static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end";

    private final ConnectorRepository repo;
    private final CredentialCipher cipher;
    private final StringRedisTemplate redis;
    private final SandboxPolicy sandbox;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)   // 重定向可绕过白名单，一律不跟
            .build();

    public ConnectorInvoker(ConnectorRepository repo, CredentialCipher cipher,
                            StringRedisTemplate redis, SandboxPolicy sandbox) {
        this.repo = repo;
        this.cipher = cipher;
        this.redis = redis;
        this.sandbox = sandbox;
    }

    public record InvokeCommand(String tenantId, String connectorId, String callId,
                                String operation, Map<String, Object> params, int timeoutMs,
                                String traceId) {
    }

    public record InvokeResult(boolean ok, Map<String, Object> data, String errorCode,
                               boolean transientError, long latencyMs, int statusCode) {
        static InvokeResult failure(String code, boolean transientError, long latencyMs) {
            return new InvokeResult(false, null, code, transientError, latencyMs, 0);
        }
    }

    public InvokeResult invoke(InvokeCommand cmd) {
        long started = System.nanoTime();
        String cacheKey = "af:conncall:" + cmd.callId();
        String cached = safeGet(cacheKey);
        if (cached != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = JSON.readValue(cached, Map.class);
                data.put("_replayed", true);
                return new InvokeResult(true, data, null, false, 0, 200);
            } catch (Exception e) {
                log.warn("ignoring malformed connector cache for {}", cmd.callId());
            }
        }

        // 原子占位：同一 callId 只有一个执行者出网，消除 get→set 之间的 TOCTOU。
        // 抢不到锁说明并发调用正在进行，返回瞬时错误让上游退避重试（届时走缓存重放）。
        String lockToken = UUID.randomUUID().toString();
        if (!acquireLock(cacheKey, lockToken)) {
            String raced = safeGet(cacheKey);
            if (raced != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = JSON.readValue(raced, Map.class);
                    data.put("_replayed", true);
                    return new InvokeResult(true, data, null, false, 0, 200);
                } catch (Exception ignored) {
                    // 落到下面的 in-flight 返回
                }
            }
            return InvokeResult.failure("CONN-INFLIGHT", true, elapsed(started));
        }
        try {
            return doInvoke(cmd, started, cacheKey);
        } finally {
            releaseLock(cacheKey, lockToken);
        }
    }

    private InvokeResult doInvoke(InvokeCommand cmd, long started, String cacheKey) {
        Optional<ConnectorRow> found = repo.findById(cmd.connectorId());
        if (found.isEmpty()) {
            return InvokeResult.failure("CONN-NOT_FOUND-001", false, elapsed(started));
        }
        ConnectorRow connector = found.get();
        if (!connector.enabled()) {
            return InvokeResult.failure("CONN-DISABLED-001", false, elapsed(started));
        }

        JsonNode config = connector.config();
        try {
            SandboxPolicy.ValidatedTarget target = buildUri(connector, config, cmd);
            URI uri = target.uri();
            // 连接前复核 DNS：若解析漂移或被改指内网则拒绝，缩小 rebinding 的 TOCTOU 窗口
            sandbox.assertNoRebinding(uri.getHost(), target.resolved());
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(Math.max(cmd.timeoutMs(), 1_000)));
            Map<String, Object> params = cmd.params() == null ? Map.of() : cmd.params();
            String method = methodOf(config, cmd.operation());
            if (!"GET".equals(method) && !"DELETE".equals(method)) {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(params), StandardCharsets.UTF_8));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            applyAuth(builder, connector, config, cmd.tenantId());
            config.path("headers").properties().forEach(e ->
                    builder.header(e.getKey(), e.getValue().asText()));

            // 流式读取而非一次性 ByteArray：上游返回 GB 级内容时不能先读进内存再判断
            HttpResponse<InputStream> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            long latency = elapsed(started);
            try (InputStream stream = response.body()) {
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    // 不跟随重定向：3xx 一律判失败，避免白名单被 302 绕过
                    return InvokeResult.failure("CONN-REDIRECT-001", false, latency);
                }
                if (response.statusCode() >= 400) {
                    boolean transientError = response.statusCode() >= 500
                            || response.statusCode() == 429;
                    return InvokeResult.failure(
                            transientError ? "CONN-HTTP-5XX" : "CONN-HTTP-4XX",
                            transientError, latency);
                }
                byte[] body = readBodyLimited(stream, MAX_RESPONSE_BYTES,
                        Duration.ofMillis(Math.max(cmd.timeoutMs(), 1_000)));
                if (body == null) {
                    return InvokeResult.failure("CONN-RESPONSE-TOO_LARGE", false, latency);
                }
                Map<String, Object> data = parseBody(body);
                safeSet(cacheKey, JSON.writeValueAsString(data));
                return new InvokeResult(true, data, null, false, latency, response.statusCode());
            }
        } catch (SandboxPolicy.Violation e) {
            log.warn("sandbox rejected connector {}: {}", cmd.connectorId(), e.getMessage());
            return InvokeResult.failure("CONN-SANDBOX-001", false, elapsed(started));
        } catch (CredentialException e) {
            return InvokeResult.failure(e.code, false, elapsed(started));
        } catch (HttpTimeoutException e) {
            return InvokeResult.failure("CONN-TIMEOUT", true, elapsed(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return InvokeResult.failure("CONN-TIMEOUT", true, elapsed(started));
        } catch (Exception e) {
            log.warn("connector {} invoke failed: {}", cmd.connectorId(), e.toString());
            return InvokeResult.failure("CONN-UPSTREAM-001", true, elapsed(started));
        }
    }

    /** 凭据缺失/过期/密钥版本不匹配：永久错误，重试无益。 */
    static final class CredentialException extends RuntimeException {
        final String code;

        CredentialException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private SandboxPolicy.ValidatedTarget buildUri(ConnectorRow connector, JsonNode config,
                                                   InvokeCommand cmd) {
        String baseUrl = config.path("baseUrl").asText("");
        if (baseUrl.isBlank()) {
            throw new CredentialException("CONN-CONFIG-001", "baseUrl missing");
        }
        List<String> hosts = JSON.convertValue(config.path("allowedHosts"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        JsonNode operation = config.path("operations").path(cmd.operation());
        String path = operation.path("path").asText("/" + cmd.operation());
        path = fillPathParams(path, cmd.params() == null ? Map.of() : cmd.params());
        String url = baseUrl.replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
        if ("GET".equals(methodOf(config, cmd.operation()))
                && cmd.params() != null && !cmd.params().isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + query(cmd.params());
        }
        return sandbox.validateWithDns(url, hosts);
    }

    private static String methodOf(JsonNode config, String operation) {
        String method = config.path("operations").path(operation).path("method").asText("POST");
        return method.toUpperCase(java.util.Locale.ROOT);
    }

    private static String fillPathParams(String path, Map<String, Object> params) {
        String result = path;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String token = "{" + e.getKey() + "}";
            if (result.contains(token)) {
                result = result.replace(token,
                        URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static String query(Map<String, Object> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((k, v) -> joiner.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)));
        return joiner.toString();
    }

    private void applyAuth(HttpRequest.Builder builder, ConnectorRow connector, JsonNode config,
                           String tenantId) {
        String authType = connector.authType();
        if ("NONE".equals(authType)) {
            return;
        }
        CredentialRow credential = repo.findCredential(connector.id())
                .orElseThrow(() -> new CredentialException("CONN-CREDENTIAL-001",
                        "credential not configured"));
        if (!credential.tenantId().equals(connector.tenantId())) {
            throw new CredentialException("CONN-CREDENTIAL-001", "credential tenant mismatch");
        }
        byte[] aad = CredentialCipher.aad(connector.id(), connector.tenantId());
        if ("OAUTH2".equals(authType)) {
            if (credential.oauthToken() == null) {
                throw new CredentialException("CONN-CREDENTIAL-001", "oauth token not configured");
            }
            JsonNode token;
            try {
                token = JSON.readTree(cipher.open(credential.oauthToken(), aad,
                        credential.keyId() == null ? cipher.keyId() : credential.keyId()));
            } catch (Exception e) {
                throw new CredentialException("CONN-CREDENTIAL-002", "oauth token unreadable");
            }
            long expiresAt = token.path("expiresAt").asLong(0);
            if (expiresAt > 0 && expiresAt < System.currentTimeMillis() / 1000) {
                throw new CredentialException("CONN-CREDENTIAL-EXPIRED", "oauth token expired");
            }
            String type = token.path("tokenType").asText("Bearer");
            builder.header("Authorization", type + " " + token.path("accessToken").asText());
            return;
        }
        if (credential.secret() == null) {
            throw new CredentialException("CONN-CREDENTIAL-001", "secret not configured");
        }
        String secret = new String(cipher.open(credential.secret(), aad,
                credential.keyId() == null ? cipher.keyId() : credential.keyId()),
                StandardCharsets.UTF_8);
        if ("BASIC".equals(authType)) {
            builder.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(
                            secret.getBytes(StandardCharsets.UTF_8)));
        } else {
            String headerName = config.path("auth").path("headerName").asText("X-API-Key");
            builder.header(headerName, secret);
        }
    }

    private static Map<String, Object> parseBody(byte[] body) {
        if (body.length == 0) {
            return Map.of();
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (node.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JSON.convertValue(node, Map.class);
                return map;
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("value", JSON.convertValue(node, Object.class));
            return wrapper;
        } catch (Exception e) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            String text = new String(body, StandardCharsets.UTF_8);
            wrapper.put("raw", text.length() > 4096 ? text.substring(0, 4096) : text);
            return wrapper;
        }
    }

    /**
     * 抢幂等执行锁。Redis 异常时 fail-open（返回 true 继续出网）——保持可用性，
     * 因为幂等结果的权威去重仍在下游工具侧按 callId 完成；此处只消除本地并发双出网。
     */
    private boolean acquireLock(String cacheKey, String token) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(cacheKey + ":lock", token, LOCK_TTL));
        } catch (Exception e) {
            log.warn("redis lock unavailable, proceed without in-flight guard: {}", e.toString());
            return true;
        }
    }

    /** 仅当锁值仍是自己写入的 token 才删除，避免 TTL 过期后误删新持有者的锁。 */
    private void releaseLock(String cacheKey, String token) {
        try {
            redis.execute(new DefaultRedisScript<>(RELEASE_LOCK_SCRIPT, Long.class),
                    List.of(cacheKey + ":lock"), token);
        } catch (Exception e) {
            log.warn("redis lock release failed: {}", e.toString());
        }
    }

    private String safeGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("redis get failed, continue without idempotency cache: {}", e.toString());
            return null;
        }
    }

    private void safeSet(String key, String value) {
        try {
            redis.opsForValue().set(key, value, CACHE_TTL);
        } catch (Exception e) {
            log.warn("redis set failed: {}", e.toString());
        }
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * 读取响应体，超过 {@code maxBytes} 立即放弃并返回 null。
     *
     * <p>读取放在独立线程并设总时长上限：仅靠 request timeout 只能约束到响应头，
     * 逐字节滴流的上游仍能把连接拖死（slow-loris）。
     */
    private static byte[] readBodyLimited(InputStream in, int maxBytes, Duration budget)
            throws Exception {
        CompletableFuture<byte[]> reading = CompletableFuture.supplyAsync(() -> {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8_192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (buffer.size() + read > maxBytes) {
                        return null;
                    }
                    buffer.write(chunk, 0, read);
                }
                return buffer.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            return reading.get(Math.max(budget.toMillis(), 1_000), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            reading.cancel(true);
            try {
                in.close();
            } catch (IOException ignored) {
                // 关闭失败不影响判定，超时已成立
            }
            throw new HttpTimeoutException("upstream body read timed out");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UncheckedIOException io) {
                throw io.getCause();
            }
            throw e;
        }
    }
}
