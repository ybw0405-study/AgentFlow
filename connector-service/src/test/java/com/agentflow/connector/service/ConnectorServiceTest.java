package com.agentflow.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.common.tenant.TenantTreePort.TenantNode;
import com.agentflow.connector.crypto.CredentialCipher;
import com.agentflow.connector.domain.ConnectorRepository;
import com.agentflow.connector.domain.ConnectorRepository.ConnectorRow;
import com.agentflow.connector.tenant.JdbcTenantTreePort;
import com.agentflow.connector.dto.ConnectorDtos.CreateRequest;
import com.agentflow.connector.dto.ConnectorDtos.IdSummary;
import com.agentflow.connector.dto.ConnectorDtos.UpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 连接器生命周期：继承可见性、写权限、跨租户 404、配置前置校验。 */
class ConnectorServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "ZGV2LW9ubHktY3JlZC1tYXN0ZXIta2V5LTMyYnl0ZXM=";

    private ConnectorRepository repo;
    private ConnectorAudit audit;
    private ConnectorInvoker invoker;
    private ConnectorService service;

    private static TenantInfo tenant(String spaceId, String userId, String role) {
        return new TenantInfo(spaceId, userId, role, null, "DIRECT");
    }

    @BeforeEach
    void setUp() {
        repo = mock(ConnectorRepository.class);
        audit = mock(ConnectorAudit.class);
        invoker = mock(ConnectorInvoker.class);
        JdbcTenantTreePort port = mock(JdbcTenantTreePort.class);
        when(port.find("space-a")).thenReturn(new TenantNode("space-a", "team", "SPACE"));
        when(port.find("team")).thenReturn(new TenantNode("team", "org", "TEAM"));
        when(port.find("org")).thenReturn(new TenantNode("org", null, "ORG"));
        service = new ConnectorService(repo, new CredentialCipher(KEY), port,
                new SandboxPolicy(false, false, h -> new java.net.InetAddress[]{
                        java.net.InetAddress.getByName("93.184.216.34")}), audit, invoker,
                inMemoryRedis());
    }

    /** 内存版 StringRedisTemplate：支撑 OAuth state nonce 的 SETNX/GETDEL 往返（P1-1 测试用）。 */
    @SuppressWarnings("unchecked")
    private static StringRedisTemplate inMemoryRedis() {
        Map<String, String> store = new HashMap<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) != null);
        when(ops.getAndDelete(anyString())).thenAnswer(inv -> store.remove(inv.getArgument(0)));
        return redis;
    }

    private static ConnectorRow row(String id, String tenantId, boolean enabled) {
        Map<String, Object> config = Map.of("baseUrl", "https://api.example.com",
                "allowedHosts", List.of("api.example.com"));
        return new ConnectorRow(id, tenantId, "c-" + id, "HTTP", "API_KEY",
                JSON.valueToTree(config), enabled, "u-admin");
    }

    @Test
    void listIncludesAncestorConnectorsMarkedInherited() {
        when(repo.listByTenants(List.of("space-a", "team", "org")))
                .thenReturn(List.of(row("org-conn", "org", true), row("space-conn", "space-a", true)));
        when(repo.findCredential(anyString())).thenReturn(Optional.empty());

        var views = service.list(tenant("space-a", "u-editor", "EDITOR"));

        assertThat(views).hasSize(2);
        assertThat(views.stream().filter(v -> v.id().equals("org-conn")).findFirst()
                .orElseThrow().inherited()).isTrue();
        assertThat(views.stream().filter(v -> v.id().equals("space-conn")).findFirst()
                .orElseThrow().inherited()).isFalse();
    }

    @Test
    void editorCannotCreateConnector() {
        assertThatThrownBy(() -> service.create(tenant("space-a", "u-editor", "EDITOR"),
                new CreateRequest("c1", "HTTP", "API_KEY", Map.of(
                        "baseUrl", "https://api.example.com",
                        "allowedHosts", List.of("api.example.com"))), "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("admin required");
        verify(repo, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void createRequiresSandboxConfig() {
        assertThatThrownBy(() -> service.create(tenant("space-a", "u-admin", "ADMIN"),
                new CreateRequest("c1", "HTTP", "API_KEY", Map.of("baseUrl", "https://x")), "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("allowedHosts");
    }

    @Test
    void createPersistsAndAudits() {
        when(repo.findByTenantAndName("space-a", "c1")).thenReturn(Optional.empty());
        when(repo.findById(anyString())).thenReturn(Optional.of(row("new-id", "space-a", true)));

        var view = service.create(tenant("space-a", "u-admin", "ADMIN"),
                new CreateRequest("c1", "HTTP", "API_KEY", Map.of(
                        "baseUrl", "https://api.example.com",
                        "allowedHosts", List.of("api.example.com"))), "trace-1");

        assertThat(view.id()).isEqualTo("new-id");
        verify(audit).record(any(), any(), anyString(), any());
    }

    @Test
    void createRejectsUnknownType() {
        assertThatThrownBy(() -> service.create(tenant("space-a", "u-admin", "ADMIN"),
                new CreateRequest("c1", "GRPC", "NONE", Map.of(
                        "baseUrl", "https://api.example.com",
                        "allowedHosts", List.of("api.example.com"))), "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsupported connector type");
    }

    @Test
    void createNormalisesBlankTypeToHttp() {
        when(repo.findByTenantAndName("space-a", "c1")).thenReturn(Optional.empty());
        when(repo.findById(anyString())).thenReturn(Optional.of(row("new-id", "space-a", true)));

        service.create(tenant("space-a", "u-admin", "ADMIN"),
                new CreateRequest("c1", " ", "NONE", Map.of(
                        "baseUrl", "https://api.example.com",
                        "allowedHosts", List.of("api.example.com"))), "t");

        verify(repo).insert(anyString(), anyString(), anyString(), eq("HTTP"),
                anyString(), anyString(), anyString());
    }

    @Test
    void probeOnlyAppliesToLlmConnectors() {
        when(repo.findById("conn-x")).thenReturn(Optional.of(row("conn-x", "space-a", true)));
        assertThatThrownBy(() -> service.probeModels(tenant("space-a", "u-admin", "ADMIN"), "conn-x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only llm connectors");
        verify(invoker, never()).probeModels(any(), anyInt());
    }

    @Test
    void probeDelegatesForLlmConnector() {
        Map<String, Object> config = Map.of("baseUrl", "https://api.deepseek.com/v1",
                "allowedHosts", List.of("api.deepseek.com"));
        when(repo.findById("conn-llm")).thenReturn(Optional.of(new ConnectorRow(
                "conn-llm", "space-a", "deepseek", "LLM", "API_KEY",
                JSON.valueToTree(config), true, "u-admin")));
        when(invoker.probeModels(any(), anyInt())).thenReturn(
                new ConnectorInvoker.ProbeResult(true, List.of("deepseek-chat"),
                        List.of(new ConnectorInvoker.ModelItem("deepseek-chat", 128_000, 8_192,
                                List.of("text"), List.of(), false)), null));

        var result = service.probeModels(tenant("space-a", "u-editor", "EDITOR"), "conn-llm");

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("deepseek-chat");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).contextWindow()).isEqualTo(128_000);
    }

    @Test
    void createAcceptsMcpType() {
        when(repo.findByTenantAndName("space-a", "c-mcp")).thenReturn(Optional.empty());
        when(repo.findById(anyString())).thenReturn(Optional.of(row("new-id", "space-a", true)));

        service.create(tenant("space-a", "u-admin", "ADMIN"),
                new CreateRequest("c-mcp", "MCP", "NONE", Map.of(
                        "baseUrl", "https://mcp.example.com/mcp",
                        "allowedHosts", List.of("mcp.example.com"))), "t");

        verify(repo).insert(anyString(), anyString(), anyString(), eq("MCP"),
                anyString(), anyString(), anyString());
    }

    @Test
    void mcpProbeOnlyAppliesToMcpConnectors() {
        when(repo.findById("conn-x")).thenReturn(Optional.of(row("conn-x", "space-a", true)));
        assertThatThrownBy(() -> service.probeMcpTools(tenant("space-a", "u-admin", "ADMIN"), "conn-x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only mcp connectors");
        verify(invoker, never()).probeMcpTools(any(), anyInt());
    }

    @Test
    void samplingConfigMustReferenceEnabledLlmInSameTenantChain() {
        Map<String, Object> base = Map.of("baseUrl", "https://mcp.example.com/mcp",
                "allowedHosts", List.of("mcp.example.com"));
        Map<String, Object> withSampling = new java.util.HashMap<>(base);
        withSampling.put("sampling", Map.of("connectorId", "llm-1"));
        when(repo.findByTenantAndName("space-a", "c-samp")).thenReturn(Optional.empty());
        when(repo.findById(anyString())).thenReturn(Optional.of(row("new-id", "space-a", true)));
        when(repo.findById("llm-1")).thenReturn(Optional.of(new ConnectorRow(
                "llm-1", "space-a", "llm", "LLM", "NONE",
                JSON.valueToTree(Map.of("baseUrl", "https://api.example.com",
                        "allowedHosts", List.of("api.example.com"))), true, "u-admin")));
        service.create(tenant("space-a", "u-admin", "ADMIN"),
                new CreateRequest("c-samp", "MCP", "NONE", withSampling), "t");
        verify(repo).insert(anyString(), anyString(), anyString(), eq("MCP"),
                anyString(), anyString(), anyString());

        // 非 LLM 目标拒绝
        when(repo.findById("llm-1")).thenReturn(Optional.of(row("llm-1", "space-a", true)));
        assertThatThrownBy(() -> service.create(tenant("space-a", "u-admin", "ADMIN"),
                new CreateRequest("c-samp2", "MCP", "NONE", withSampling), "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("enabled LLM connector");

        // 跨租户拒绝
        when(repo.findById("llm-1")).thenReturn(Optional.of(new ConnectorRow(
                "llm-1", "other-tenant", "llm", "LLM", "NONE",
                JSON.valueToTree(base), true, "u-admin")));
        assertThatThrownBy(() -> service.create(tenant("space-a", "u-admin", "ADMIN"),
                new CreateRequest("c-samp3", "MCP", "NONE", withSampling), "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not visible");

        // 上限越界拒绝
        when(repo.findById("llm-1")).thenReturn(Optional.of(new ConnectorRow(
                "llm-1", "space-a", "llm", "LLM", "NONE",
                JSON.valueToTree(base), true, "u-admin")));
        Map<String, Object> overCap = new java.util.HashMap<>(base);
        overCap.put("sampling", Map.of("connectorId", "llm-1", "maxTokens", 99999));
        assertThatThrownBy(() -> service.create(tenant("space-a", "u-admin", "ADMIN"),
                new CreateRequest("c-samp4", "MCP", "NONE", overCap), "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sampling.maxTokens");
    }

    @Test
    void samplingCannotReferenceItself() {
        when(repo.findById("conn-self")).thenReturn(Optional.of(new ConnectorRow(
                "conn-self", "space-a", "self", "MCP", "NONE",
                JSON.valueToTree(Map.of("baseUrl", "https://mcp.example.com/mcp",
                        "allowedHosts", List.of("mcp.example.com"))), true, "u-admin")));
        Map<String, Object> config = Map.of("baseUrl", "https://mcp.example.com/mcp",
                "allowedHosts", List.of("mcp.example.com"),
                "sampling", Map.of("connectorId", "conn-self"));
        assertThatThrownBy(() -> service.update(tenant("space-a", "u-admin", "ADMIN"),
                "conn-self", new UpdateRequest(null, null, config, null), "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot reference itself");
    }

    @Test
    void resourceAndPromptProbesGuardType() {
        when(repo.findById("conn-x")).thenReturn(Optional.of(row("conn-x", "space-a", true)));
        assertThatThrownBy(() -> service.probeMcpResources(tenant("space-a", "u-admin", "ADMIN"), "conn-x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only mcp connectors");
        assertThatThrownBy(() -> service.probeMcpPrompts(tenant("space-a", "u-admin", "ADMIN"), "conn-x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only mcp connectors");
        verify(invoker, never()).probeMcpResources(any(), anyInt());
        verify(invoker, never()).probeMcpPrompts(any(), anyInt());
    }

    @Test
    void mcpProbeDelegatesForMcpConnector() {
        Map<String, Object> config = Map.of("baseUrl", "https://mcp.example.com/mcp",
                "allowedHosts", List.of("mcp.example.com"));
        when(repo.findById("conn-mcp")).thenReturn(Optional.of(new ConnectorRow(
                "conn-mcp", "space-a", "crm-mcp", "MCP", "NONE",
                JSON.valueToTree(config), true, "u-admin")));
        when(invoker.probeMcpTools(any(), anyInt())).thenReturn(
                new ConnectorInvoker.McpToolsResult(true,
                        List.of(new McpProtocol.ToolItem("crm-search", "查客户", Map.of())), null));

        var result = service.probeMcpTools(tenant("space-a", "u-editor", "EDITOR"), "conn-mcp");

        assertThat(result.ok()).isTrue();
        assertThat(result.tools()).hasSize(1);
        assertThat(result.tools().get(0).name()).isEqualTo("crm-search");
    }

    @Test
    void inheritedConnectorIsNotWritable() {
        when(repo.findById("org-conn")).thenReturn(Optional.of(row("org-conn", "org", true)));
        assertThatThrownBy(() -> service.update(tenant("space-a", "u-admin", "ADMIN"),
                "org-conn", null, "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("resource not found");
    }

    @Test
    void foreignConnectorIs404() {
        when(repo.findById("other-conn"))
                .thenReturn(Optional.of(row("other-conn", "other-space", true)));
        assertThatThrownBy(() -> service.delete(tenant("space-a", "u-admin", "ADMIN"),
                "other-conn", "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("resource not found");
    }

    private static ConnectorRow oauthRow() {
        Map<String, Object> config = Map.of(
                "baseUrl", "https://api.example.com",
                "allowedHosts", List.of("api.example.com"),
                "oauth", Map.of(
                        "authorizationUrl", "https://api.example.com/oauth/authorize",
                        "tokenUrl", "https://api.example.com/oauth/token",
                        "clientId", "cid", "redirectUri", "https://app/cb", "scope", "read"));
        return new ConnectorRow("oauth-conn", "space-a", "oauth", "HTTP", "OAUTH2",
                JSON.valueToTree(config), true, "u-admin");
    }

    @Test
    void oauthAuthorizeUrlCarriesSignedState() {
        when(repo.findById("oauth-conn")).thenReturn(Optional.of(oauthRow()));
        Map<String, String> data = service.oauthAuthorizeUrl(
                tenant("space-a", "u-admin", "ADMIN"), "oauth-conn");
        assertThat(data.get("url")).contains("response_type=code").contains("client_id=cid");
        assertThat(data.get("state")).contains(".");
    }

    @Test
    void oauthCallbackRejectsTamperedState() {
        when(repo.findById("oauth-conn")).thenReturn(Optional.of(oauthRow()));
        Map<String, String> data = service.oauthAuthorizeUrl(
                tenant("space-a", "u-admin", "ADMIN"), "oauth-conn");
        String tampered = data.get("state") + "x";
        assertThatThrownBy(() -> service.oauthCallback(
                tenant("space-a", "u-admin", "ADMIN"), "oauth-conn", "code", tampered, "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("oauth state");
    }

    @Test
    void visibleEnabledFiltersDisabled() {
        when(repo.listByTenants(any())).thenReturn(List.of(
                row("on", "space-a", true), row("off", "space-a", false)));
        assertThat(service.visibleEnabled("space-a")).extracting(IdSummary::id)
                .containsExactly("on");
    }

    @Test
    void oauthCallbackRejectsReplayedState() {
        // P1-1 回归：同一 state 只能用一次（Redis GETDEL 原子消费），重放必须失败
        when(repo.findById("oauth-conn")).thenReturn(Optional.of(oauthRow()));
        Map<String, String> data = service.oauthAuthorizeUrl(
                tenant("space-a", "u-admin", "ADMIN"), "oauth-conn");
        String state = data.get("state");
        // 第一次回调会走到换 token（会因 fake HTTP 失败），但 state 已被消费
        try {
            service.oauthCallback(tenant("space-a", "u-admin", "ADMIN"), "oauth-conn", "code", state, "t");
        } catch (Exception ignored) {
            // 换 token 阶段的异常无所谓，关键是 state 已消费
        }
        // 第二次用同一 state：必须在 state 校验阶段就被拒（replayed/unknown）
        assertThatThrownBy(() -> service.oauthCallback(
                tenant("space-a", "u-admin", "ADMIN"), "oauth-conn", "code", state, "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("oauth state replayed");
    }
}
