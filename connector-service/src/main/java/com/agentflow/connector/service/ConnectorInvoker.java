package com.agentflow.connector.service;

import com.agentflow.connector.crypto.CredentialCipher;
import com.agentflow.connector.domain.ConnectorRepository;
import com.agentflow.connector.domain.ConnectorRepository.ConnectorRow;
import com.agentflow.connector.domain.ConnectorRepository.CredentialRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.UUID;
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
    private static final int MAX_MODEL_ENTRIES = 200;
    private static final int MAX_MODEL_ID_LENGTH = 200;
    /** 模态与参数列表都只用于展示，截断到前 16 项足够，也避免配置体积被上游撑大。 */
    private static final int MAX_FIELD_ENTRIES = 16;
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

    /**
     * 探测回来的单个模型：只透传能做能力标注的字段。
     *
     * <p>字段名在网关之间并不统一（`context_length` / `context_window` / `max_model_len`…），
     * 这里按已知别名取第一个有值的；取不到就是 null，界面据此退回按模型名推断。
     *
     * <p>刻意不在这里判「是不是推理型」：标签规则在引擎（Python）与前端（TS）各有一份，
     * 再抄一份到 Java 只会让三个地方慢慢长歪。本层只做搬运——上游给什么字段就转什么字段，
     * 一个字节的启发式都不加。
     */
    public record ModelItem(String id, Integer contextWindow, Integer maxOutputTokens,
                            List<String> inputModalities, List<String> supportedParameters,
                            boolean reasoningEffort) {
    }

    /** 模型清单探测结果。只回模型 id 与能力字段，不回上游响应体（可能含账号信息）。 */
    public record ProbeResult(boolean ok, List<String> models, List<ModelItem> items,
                              String errorCode) {
        static ProbeResult failure(String code) {
            return new ProbeResult(false, List.of(), List.of(), code);
        }
    }

    /**
     * 读取某个连接所指向端点的 {@code /models} 清单，供前端在建连接时挑选模型。
     *
     * <p>不走 {@link #invoke}：那条路带按 callId 的幂等缓存与并发锁，探测是交互式操作，
     * 每次都是新 callId，写缓存只会留下无人读取的键。这里复用同一套 URI 构建、白名单
     * 校验与凭据注入，保证与真实调用完全一致的出网口径。
     */
    public ProbeResult probeModels(ConnectorRow connector, int timeoutMs) {
        JsonNode config = connector.config();
        try {
            // operations.models 未声明时 buildUri 回落到 /models，与 chat 的路径解析规则一致
            InvokeCommand cmd = new InvokeCommand(connector.tenantId(), connector.id(),
                    UUID.randomUUID().toString(), "models", Map.of(), timeoutMs, "");
            SandboxPolicy.ValidatedTarget target = buildUri(connector, config, cmd);
            sandbox.assertNoRebinding(target.uri().getHost(), target.resolved());
            HttpRequest.Builder builder = HttpRequest.newBuilder(target.uri())
                    .timeout(Duration.ofMillis(Math.max(timeoutMs, 1_000)))
                    .header("Accept", "application/json")
                    .method("GET", HttpRequest.BodyPublishers.noBody());
            applyAuth(builder, connector, config, connector.tenantId());

            HttpResponse<InputStream> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    return ProbeResult.failure("CONN-REDIRECT-001");
                }
                if (response.statusCode() >= 400) {
                    return ProbeResult.failure(response.statusCode() >= 500
                            || response.statusCode() == 429 ? "CONN-HTTP-5XX" : "CONN-HTTP-4XX");
                }
                byte[] body = BoundedBodyReader.read(stream, MAX_RESPONSE_BYTES,
                        Duration.ofMillis(Math.max(timeoutMs, 1_000)));
                if (body == null) {
                    return ProbeResult.failure("CONN-RESPONSE-TOO_LARGE");
                }
                Map<String, Object> parsed = parseBody(body);
                if (!(parsed.get("data") instanceof List<?>)) {
                    // 端点不像 OpenAI 兼容服务，或 /models 被网关改写；给出可区分的错误码
                    return ProbeResult.failure("CONN-RESPONSE-001");
                }
                List<ModelItem> items = modelItems(parsed);
                return new ProbeResult(true, items.stream().map(ModelItem::id).toList(), items,
                        null);
            }
        } catch (SandboxPolicy.Violation e) {
            log.warn("sandbox rejected model probe for {}: {}", connector.id(), e.getMessage());
            return ProbeResult.failure("CONN-SANDBOX-001");
        } catch (CredentialException e) {
            return ProbeResult.failure(e.code);
        } catch (HttpTimeoutException e) {
            return ProbeResult.failure("CONN-TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.failure("CONN-TIMEOUT");
        } catch (Exception e) {
            log.warn("model probe for {} failed: {}", connector.id(), e.toString());
            return ProbeResult.failure("CONN-UPSTREAM-001");
        }
    }

    /**
     * 从 OpenAI 兼容响应里取 {@code data[]}：按 id 去重排序，条数、id 长度与列表字段都截断。
     *
     * <p>探测结果会被写进连接配置并落库，所以这里只搬白名单字段——上游 `/models` 还有
     * owner、created、权限等与选模型无关的内容，多搬一个就多一份会过期的数据。
     */
    static List<ModelItem> modelItems(Map<String, Object> body) {
        Object raw = body.get("data");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        Map<String, ModelItem> byId = new TreeMap<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String id = modelId(map.get("id"));
            if (id == null) {
                continue;
            }
            byId.putIfAbsent(id, new ModelItem(id, contextWindow(map), maxOutputTokens(map),
                    modalities(map), parameters(map), supportsEffort(map)));
            if (byId.size() >= MAX_MODEL_ENTRIES) {
                break;
            }
        }
        return List.copyOf(byId.values());
    }

    private static String modelId(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > MAX_MODEL_ID_LENGTH
                ? trimmed.substring(0, MAX_MODEL_ID_LENGTH) : trimmed;
    }

    private static Integer positiveInt(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        long parsed = number.longValue();
        return parsed <= 0 ? null : (int) Math.min(parsed, Integer.MAX_VALUE);
    }

    private static Integer firstInt(Map<?, ?> source, String... names) {
        for (String name : names) {
            Integer found = positiveInt(source.get(name));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Integer contextWindow(Map<?, ?> item) {
        return firstInt(item, "context_length", "context_window", "max_context_length",
                "max_model_len");
    }

    /** 输出上限有的端点写在顶层，有的挂在 {@code top_provider} 下。 */
    private static Integer maxOutputTokens(Map<?, ?> item) {
        Integer direct = firstInt(item, "max_output_tokens", "max_completion_tokens", "max_tokens");
        if (direct != null) {
            return direct;
        }
        return item.get("top_provider") instanceof Map<?, ?> top
                ? firstInt(top, "max_completion_tokens", "max_output_tokens") : null;
    }

    /** 输入模态可能写在顶层，也可能挂在 {@code architecture} 下，两种都认。 */
    private static List<String> modalities(Map<?, ?> item) {
        for (Object source : new Object[] {item, item.get("architecture")}) {
            if (source instanceof Map<?, ?> map) {
                List<String> values = strings(map.get("input_modalities"));
                if (!values.isEmpty()) {
                    return values;
                }
            }
        }
        return List.of();
    }

    private static List<String> parameters(Map<?, ?> item) {
        return strings(item.get("supported_parameters"));
    }

    /** {@code effort.supported_levels} 这类「思考强度」开关只有推理型模型才会给。 */
    private static boolean supportsEffort(Map<?, ?> item) {
        return item.get("effort") instanceof Map<?, ?> effort
                && effort.get("supported_levels") instanceof List<?> levels
                && !levels.isEmpty();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object entry : list) {
            if (!(entry instanceof String text) || text.isBlank()) {
                continue;
            }
            values.add(text.trim().toLowerCase(Locale.ROOT));
            if (values.size() >= MAX_FIELD_ENTRIES) {
                break;
            }
        }
        return List.copyOf(values);
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
                byte[] body = BoundedBodyReader.read(stream, MAX_RESPONSE_BYTES,
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
        return method.toUpperCase(Locale.ROOT);
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
            builder.header(headerName, authValue(config, secret));
        }
    }

    /**
     * 凭据头取值。OpenAI 兼容端点要求 {@code Authorization: Bearer sk-xxx}，即密文前要加固定
     * 前缀；前缀由连接配置声明（{@code auth.valuePrefix}），默认空以兼容既有连接器。
     *
     * <p>前缀来自配置而非用户输入的密钥，仍做字符校验：header 值里出现 CR/LF 会被 JDK 拒绝，
     * 但报错发生在出网时，早失败并给出明确原因更利于排查。
     */
    static String authValue(JsonNode config, String secret) {
        String prefix = config.path("auth").path("valuePrefix").asText("");
        if (prefix.isEmpty()) {
            return secret;
        }
        if (prefix.length() > 32 || prefix.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            throw new CredentialException("CONN-CONFIG-001", "auth.valuePrefix malformed");
        }
        return prefix + secret;
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
}
