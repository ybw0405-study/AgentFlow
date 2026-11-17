package com.agentflow.connector.web;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.tenant.TenantContext;
import com.agentflow.connector.service.ConnectorService;
import com.agentflow.connector.web.ConnectorDtos.AuthorizeRequest;
import com.agentflow.connector.web.ConnectorDtos.ConnectorView;
import com.agentflow.connector.web.ConnectorDtos.CreateRequest;
import com.agentflow.connector.web.ConnectorDtos.OAuthCallbackRequest;
import com.agentflow.connector.web.ConnectorDtos.UpdateRequest;
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

    private static String traceId(HttpServletRequest request) {
        return org.slf4j.MDC.get(com.agentflow.common.trace.TraceIdContext.MDC_KEY);
    }
}
