package com.agentflow.connector.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.common.tenant.TenantHierarchyResolver;
import com.agentflow.connector.crypto.CredentialCipher;
import com.agentflow.connector.domain.ConnectorRepository;
import com.agentflow.connector.domain.ConnectorRepository.ConnectorRow;
import com.agentflow.connector.dto.ConnectorDtos.ConnectorView;
import com.agentflow.connector.dto.ConnectorDtos.CreateRequest;
import com.agentflow.connector.dto.ConnectorDtos.IdSummary;
import com.agentflow.connector.dto.ConnectorDtos.UpdateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 连接器生命周期：CRUD（按租户树可见）+ 凭据托管 + OAuth2 code flow。
 *
 * <p>权限：列表对租户成员可见（含祖先租户共享的连接器）；写操作（增删改/授权）
 * 仅连接器所属租户的 ADMIN+，且跨租户一律 404。
 */
@Service
public class ConnectorService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);
    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(10);
    /** 模型清单探测是交互操作，用户就在页面上等，超时要短于一次执行。 */
    private static final int PROBE_TIMEOUT_MS = 15_000;
    /**
     * 连接器类型。LLM 表示「模型接入」：baseUrl 指向 OpenAI 兼容端点，凭据是 API Key，
     * operations 声明 chat（POST /chat/completions）与 models（GET /models）。
     * 与 HTTP 工具连接器共用同一套加密、归属与沙箱策略，区别只在于引擎怎么用它。
     */
    private static final java.util.Set<String> TYPES = java.util.Set.of("HTTP", "LLM");
    /** token 响应只有几十字节，上限设小一些；解析前先兜住异常上游。 */
    private static final int MAX_TOKEN_RESPONSE_BYTES = 64 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final ConnectorRepository repo;
    private final CredentialCipher cipher;
    private final TenantHierarchyResolver resolver;
    private final SandboxPolicy sandbox;
    private final ConnectorAudit audit;
    private final ConnectorInvoker invoker;

    public ConnectorService(ConnectorRepository repo, CredentialCipher cipher,
                            com.agentflow.connector.tenant.JdbcTenantTreePort treePort,
                            SandboxPolicy sandbox, ConnectorAudit audit,
                            ConnectorInvoker invoker) {
        this.repo = repo;
        this.cipher = cipher;
        this.resolver = new TenantHierarchyResolver(treePort);
        this.sandbox = sandbox;
        this.audit = audit;
        this.invoker = invoker;
    }

    public List<ConnectorView> list(TenantInfo tenant) {
        List<String> chain = chainOf(tenant.tenantId());
        List<ConnectorView> views = new ArrayList<>();
        for (ConnectorRow row : repo.listByTenants(chain)) {
            boolean inherited = !row.tenantId().equals(tenant.tenantId());
            views.add(view(row, inherited, repo.findCredential(row.id()).isPresent()));
        }
        return views;
    }

    public ConnectorView create(TenantInfo tenant, CreateRequest req, String traceId) {
        requireAdmin(tenant);
        String name = requireText(req.name(), "name");
        String type = requireType(req.type());
        String authType = req.authType() == null ? "NONE" : req.authType().toUpperCase();
        validateAuthType(authType);
        Map<String, Object> config = req.config() == null ? Map.of() : req.config();
        validateSandboxConfig(config);
        if (repo.findByTenantAndName(tenant.tenantId(), name).isPresent()) {
            throw new BusinessException(ErrorCode.COM_CONFLICT_001, "connector name exists");
        }
        String id = UUID.randomUUID().toString();
        repo.insert(id, tenant.tenantId(), name, type, authType, writeJson(config),
                tenant.userId());
        audit.record(tenant, traceId, "CONN_CREATED",
                Map.of("connectorId", id, "name", name, "authType", authType));
        return view(repo.findById(id).orElseThrow(), false, false);
    }

    public ConnectorView update(TenantInfo tenant, String id, UpdateRequest req, String traceId) {
        ConnectorRow row = requireOwned(tenant, id);
        requireAdmin(tenant);
        String name = req.name() == null ? row.name() : requireText(req.name(), "name");
        String authType = req.authType() == null ? row.authType() : req.authType().toUpperCase();
        validateAuthType(authType);
        Map<String, Object> config = req.config() == null
                ? JSON.convertValue(row.config(), Map.class) : req.config();
        validateSandboxConfig(config);
        boolean enabled = req.enabled() == null || req.enabled();
        repo.updateConfig(id, tenant.tenantId(), name, authType, writeJson(config), enabled);
        audit.record(tenant, traceId, "CONN_UPDATED",
                Map.of("connectorId", id, "enabled", enabled));
        return view(repo.findById(id).orElseThrow(), false,
                repo.findCredential(id).isPresent());
    }

    public void delete(TenantInfo tenant, String id, String traceId) {
        requireOwned(tenant, id);
        requireAdmin(tenant);
        repo.softDelete(id, tenant.tenantId());
        audit.record(tenant, traceId, "CONN_DELETED", Map.of("connectorId", id));
    }

    /**
     * 保存密文。API_KEY/BASIC 存调用凭据；OAUTH2 存的是 client_secret，
     * access_token 只能由授权码流程写入（见 {@link #oauthCallback}）。
     */
    public void authorize(TenantInfo tenant, String id, String secret, String traceId) {
        ConnectorRow row = requireOwned(tenant, id);
        requireAdmin(tenant);
        String plaintext = requireText(secret, "secret");
        byte[] sealed = cipher.seal(plaintext.getBytes(StandardCharsets.UTF_8),
                CredentialCipher.aad(row.id(), row.tenantId()));
        repo.upsertCredential(UUID.randomUUID().toString(), row.id(), row.tenantId(), sealed,
                null, cipher.keyId(), null, null);
        audit.record(tenant, traceId, "CONN_AUTHORIZED",
                Map.of("connectorId", id, "authType", row.authType()));
    }

    /** 生成 OAuth2 授权地址；state 带 HMAC 防伪与 10 分钟时效。 */
    public Map<String, String> oauthAuthorizeUrl(TenantInfo tenant, String id) {
        // 授权会写入凭据，属写操作：与 authorize()/callback 统一为 owner 租户 ADMIN，
        // 否则子租户可对祖先共享连接器发起授权并覆盖其 OAuth 凭据（跨租户写越权）。
        ConnectorRow row = requireOwned(tenant, id);
        if (!"OAUTH2".equals(row.authType())) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "connector is not OAUTH2");
        }
        JsonNode oauth = row.config().path("oauth");
        String authorizationUrl = oauth.path("authorizationUrl").asText("");
        String clientId = oauth.path("clientId").asText("");
        if (authorizationUrl.isBlank() || clientId.isBlank()) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001,
                    "oauth.authorizationUrl/clientId required");
        }
        // 与 tokenUrl 同样过出网沙箱：禁止 javascript:/非白名单域名/私网地址，防开放重定向与钓鱼。
        List<String> authHosts = stringList(oauth.path("allowedHosts").isMissingNode()
                ? row.config().path("allowedHosts") : oauth.path("allowedHosts"));
        sandbox.validate(authorizationUrl, authHosts);
        String state = issueState(row.id(), tenant.tenantId());
        String url = authorizationUrl + (authorizationUrl.contains("?") ? "&" : "?")
                + "response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(oauth.path("redirectUri").asText(""))
                + "&scope=" + enc(oauth.path("scope").asText(""))
                + "&state=" + enc(state);
        return Map.of("url", url, "state", state);
    }

    /** 回调：校验 state → 换 token → 加密存储。 */
    public void oauthCallback(TenantInfo tenant, String id, String code, String state,
                              String traceId) {
        // 回调写 access_token 到 row.tenantId()（连接器 owner），必须由 owner 租户 ADMIN 触发；
        // 原先的 requireVisible 允许子租户用自身 state 覆写祖先连接器的凭据。
        ConnectorRow row = requireOwned(tenant, id);
        requireAdmin(tenant);
        verifyState(state, row.id(), tenant.tenantId());
        JsonNode oauth = row.config().path("oauth");
        String tokenUrl = oauth.path("tokenUrl").asText("");
        if (tokenUrl.isBlank()) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "oauth.tokenUrl required");
        }
        List<String> hosts = stringList(oauth.path("allowedHosts").isMissingNode()
                ? row.config().path("allowedHosts") : oauth.path("allowedHosts"));
        URI uri = sandbox.validate(tokenUrl, hosts);
        var cred = repo.findCredential(row.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.COM_VALIDATION_001,
                        "oauth client secret not configured"));
        String clientSecret = new String(cipher.open(cred.secret(),
                CredentialCipher.aad(row.id(), row.tenantId()),
                cred.keyId() == null ? cipher.keyId() : cred.keyId()), StandardCharsets.UTF_8);
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&client_id=" + enc(oauth.path("clientId").asText(""))
                + "&client_secret=" + enc(clientSecret)
                + "&redirect_uri=" + enc(oauth.path("redirectUri").asText(""));
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TOKEN_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<InputStream> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] payload;
            try (InputStream stream = response.body()) {
                payload = BoundedBodyReader.read(stream, MAX_TOKEN_RESPONSE_BYTES, TOKEN_TIMEOUT);
            }
            if (payload == null) {
                throw new BusinessException(ErrorCode.AGT_UPSTREAM_001,
                        "oauth token endpoint response exceeds limit");
            }
            if (response.statusCode() >= 400) {
                throw new BusinessException(ErrorCode.AGT_UPSTREAM_001,
                        "oauth token endpoint returned " + response.statusCode());
            }
            JsonNode token = JSON.readTree(payload);
            String accessToken = token.path("access_token").asText("");
            if (accessToken.isBlank()) {
                throw new BusinessException(ErrorCode.AGT_UPSTREAM_001,
                        "oauth token endpoint returned no access_token");
            }
            long expiresIn = token.path("expires_in").asLong(0);
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("accessToken", accessToken);
            stored.put("tokenType", token.path("token_type").asText("Bearer"));
            stored.put("scope", token.path("scope").asText(""));
            stored.put("expiresAt", expiresIn > 0
                    ? System.currentTimeMillis() / 1000 + expiresIn : 0);
            byte[] sealed = cipher.seal(writeJson(stored).getBytes(StandardCharsets.UTF_8),
                    CredentialCipher.aad(row.id(), row.tenantId()));
            repo.upsertCredential(UUID.randomUUID().toString(), row.id(), row.tenantId(),
                    repo.findCredential(row.id()).map(c -> c.secret()).orElse(null), sealed,
                    cipher.keyId(), expiresIn > 0
                            ? new Timestamp(System.currentTimeMillis() + expiresIn * 1000) : null,
                    stored.get("scope").toString());
            audit.record(tenant, traceId, "CONN_OAUTH_GRANTED",
                    Map.of("connectorId", id, "expiresIn", expiresIn));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001,
                    "oauth token exchange failed");
        }
    }

    /** 内部：模块间查询某空间可见（已启用）的连接器。返回 DTO，避免 domain record 泄漏到 web 层。 */
    public List<IdSummary> visibleEnabled(String spaceId) {
        return repo.listByTenants(chainOf(spaceId)).stream()
                .filter(ConnectorRow::enabled)
                .map(row -> new IdSummary(row.id(), row.name(), row.tenantId()))
                .toList();
    }

    /**
     * 探测该模型连接指向的端点支持哪些模型，用于建连接时挑选，避免让用户凭空猜模型名。
     *
     * <p>只要可见即可探测（继承来的连接也能验一次能不能用），但只有 LLM 类型可探测：
     * 普通工具连接器的 {@code /models} 没有统一语义。
     */
    public ConnectorInvoker.ProbeResult probeModels(TenantInfo tenant, String id) {
        ConnectorRow row = requireVisible(tenant, id);
        if (!"LLM".equals(row.type())) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001,
                    "only llm connectors expose a model list");
        }
        return invoker.probeModels(row, PROBE_TIMEOUT_MS);
    }

    public List<String> chainOf(String spaceId) {
        return resolver.chainOf(spaceId).stream().map(n -> n.id()).toList();
    }

    private ConnectorRow requireVisible(TenantInfo tenant, String id) {
        ConnectorRow row = repo.findById(id)
                .orElseThrow(() -> notFound());
        if (!chainOf(tenant.tenantId()).contains(row.tenantId())) {
            throw notFound();
        }
        return row;
    }

    private ConnectorRow requireOwned(TenantInfo tenant, String id) {
        ConnectorRow row = requireVisible(tenant, id);
        if (!row.tenantId().equals(tenant.tenantId())) {
            throw notFound();   // 祖先共享的连接器只读，不暴露可写性
        }
        return row;
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
    }

    private static void requireAdmin(TenantInfo tenant) {
        if (!tenant.atLeast("ADMIN")) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "admin required");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, field + " required");
        }
        return value.trim();
    }

    private static void validateAuthType(String authType) {
        if (!List.of("NONE", "API_KEY", "BASIC", "OAUTH2").contains(authType)) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "unsupported authType");
        }
    }

    /** 类型收敛：空值按 HTTP（历史行为），未知类型拒绝，避免存进一个没人认识的值。 */
    private static String requireType(String raw) {
        String type = raw == null || raw.isBlank()
                ? "HTTP" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "unsupported connector type");
        }
        return type;
    }

    /** 沙箱配置前置校验：宁可创建时失败，也不要运行时才发现出不去网。 */
    private static void validateSandboxConfig(Map<String, Object> config) {
        Object baseUrl = config.get("baseUrl");
        if (!(baseUrl instanceof String s) || s.isBlank()) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "config.baseUrl required");
        }
        List<String> hosts = stringList(JSON.valueToTree(config.get("allowedHosts")));
        if (hosts.isEmpty()) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001,
                    "config.allowedHosts required");
        }
    }

    private ConnectorView view(ConnectorRow row, boolean inherited, boolean hasCredential) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = JSON.convertValue(row.config(), Map.class);
        return new ConnectorView(row.id(), row.tenantId(), row.name(), row.type(),
                row.authType(), config, row.enabled(), hasCredential, inherited);
    }

    private String issueState(String connectorId, String tenantId) {
        long expires = System.currentTimeMillis() / 1000 + OAUTH_STATE_TTL.toSeconds();
        String payload = connectorId + "|" + tenantId + "|" + expires;
        byte[] signature = cipher.sign(payload.getBytes(StandardCharsets.UTF_8));
        byte[] trimmed = new byte[16];
        System.arraycopy(signature, 0, trimmed, 0, 16);
        return base64(payload.getBytes(StandardCharsets.UTF_8)) + "." + base64(trimmed);
    }

    private void verifyState(String state, String connectorId, String tenantId) {
        try {
            String[] parts = state.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]),
                    StandardCharsets.UTF_8);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            byte[] expected = cipher.sign(payload.getBytes(StandardCharsets.UTF_8));
            byte[] trimmed = java.util.Arrays.copyOf(expected, 16);
            if (!java.security.MessageDigest.isEqual(trimmed, signature)) {
                throw new BusinessException(ErrorCode.COM_VALIDATION_001, "invalid oauth state");
            }
            String[] fields = payload.split("\\|");
            if (!fields[0].equals(connectorId) || !fields[1].equals(tenantId)) {
                throw new BusinessException(ErrorCode.COM_VALIDATION_001, "oauth state mismatch");
            }
            if (Long.parseLong(fields[2]) < System.currentTimeMillis() / 1000) {
                throw new BusinessException(ErrorCode.COM_VALIDATION_001, "oauth state expired");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "invalid oauth state");
        }
    }

    /** 签名截断为 16 字节后随 state 传输，校验时以完整签名截断比较。 */
    private static String base64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "invalid config json");
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
