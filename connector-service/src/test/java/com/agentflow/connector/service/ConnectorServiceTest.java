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
import com.agentflow.connector.web.ConnectorDtos.CreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
                        java.net.InetAddress.getByName("93.184.216.34")}), audit, invoker);
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
                new ConnectorInvoker.ProbeResult(true, List.of("deepseek-chat"), null));

        var result = service.probeModels(tenant("space-a", "u-editor", "EDITOR"), "conn-llm");

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("deepseek-chat");
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
        assertThat(service.visibleEnabled("space-a")).extracting(ConnectorRow::id)
                .containsExactly("on");
    }
}
