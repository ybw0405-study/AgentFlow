package com.agentflow.connector.web;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.tenant.TenantContext;
import com.agentflow.connector.service.ConnectorService;
import com.agentflow.connector.dto.ConnectorDtos.AuthorizeRequest;
import com.agentflow.connector.dto.ConnectorDtos.ConnectorView;
import com.agentflow.connector.dto.ConnectorDtos.CreateRequest;
import com.agentflow.connector.dto.ConnectorDtos.OAuthCallbackRequest;
import com.agentflow.connector.dto.ConnectorDtos.UpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 连接器管理 API（ADMIN+；列表对租户成员开放）。 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorController {

    private final ConnectorService connectors;

    public ConnectorController(ConnectorService connectors) {
        this.connectors = connectors;
    }

    @GetMapping
    public ApiResponse<List<ConnectorView>> list() {
        return ApiResponse.ok(connectors.list(TenantContext.require()));
    }

    @PostMapping
    public ApiResponse<ConnectorView> create(HttpServletRequest request,
                                             @RequestBody CreateRequest body) {
        return ApiResponse.ok(connectors.create(TenantContext.require(), body, traceId(request)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<ConnectorView> update(HttpServletRequest request,
                                             @PathVariable String id,
                                             @RequestBody UpdateRequest body) {
        return ApiResponse.ok(connectors.update(TenantContext.require(), id, body,
                traceId(request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable String id) {
        connectors.delete(TenantContext.require(), id, traceId(request));
        return ApiResponse.ok(null);
    }

    @PutMapping("/{id}/authorize")
    public ApiResponse<Void> authorize(HttpServletRequest request, @PathVariable String id,
                                       @RequestBody AuthorizeRequest body) {
        connectors.authorize(TenantContext.require(), id, body.secret(), traceId(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/oauth/authorize-url")
    public ApiResponse<Map<String, String>> oauthUrl(@PathVariable String id) {
        return ApiResponse.ok(connectors.oauthAuthorizeUrl(TenantContext.require(), id));
    }

    @PostMapping("/{id}/oauth/callback")
    public ApiResponse<Void> oauthCallback(HttpServletRequest request, @PathVariable String id,
                                           @RequestBody OAuthCallbackRequest body) {
        connectors.oauthCallback(TenantContext.require(), id, body.code(), body.state(),
                traceId(request));
        return ApiResponse.ok(null);
    }

    /**
     * 拉取该模型连接可用模型清单。凭据在服务端解密并注入请求头，前端只拿到模型 id，
     * 端点地址与密钥都不出本进程。
     */
    @PostMapping("/{id}/models/probe")
    public ApiResponse<com.agentflow.connector.service.ConnectorInvoker.ProbeResult> probeModels(
            @PathVariable String id) {
        return ApiResponse.ok(connectors.probeModels(TenantContext.require(), id));
    }

    /**
     * 拉取该 MCP 连接暴露的工具清单。凭据在服务端解密并注入请求头，
     * 前端只拿到 name/description/inputSchema，端点地址与密钥都不出本进程。
     */
    @PostMapping("/{id}/mcp/tools")
    public ApiResponse<com.agentflow.connector.service.ConnectorInvoker.McpToolsResult> probeMcpTools(
            @PathVariable String id) {
        return ApiResponse.ok(connectors.probeMcpTools(TenantContext.require(), id));
    }

    /** 拉取该 MCP 连接暴露的资源清单（uri/name/description/mimeType）。 */
    @PostMapping("/{id}/mcp/resources")
    public ApiResponse<com.agentflow.connector.service.ConnectorInvoker.McpResourcesResult> probeMcpResources(
            @PathVariable String id) {
        return ApiResponse.ok(connectors.probeMcpResources(TenantContext.require(), id));
    }

    /** 拉取该 MCP 连接暴露的提示清单（name/description/arguments 摘要）。 */
    @PostMapping("/{id}/mcp/prompts")
    public ApiResponse<com.agentflow.connector.service.ConnectorInvoker.McpPromptsResult> probeMcpPrompts(
            @PathVariable String id) {
        return ApiResponse.ok(connectors.probeMcpPrompts(TenantContext.require(), id));
    }

    private static String traceId(HttpServletRequest request) {
        return org.slf4j.MDC.get(com.agentflow.common.trace.TraceIdContext.MDC_KEY);
    }
}
