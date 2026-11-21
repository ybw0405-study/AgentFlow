package com.agentflow.connector.dto;

/** 连接器 API 请求/响应体。config 内不含任何密钥（密钥只走 /authorize）。 */
public final class ConnectorDtos {

    private ConnectorDtos() {
    }

    public record CreateRequest(String name, String type, String authType,
                                java.util.Map<String, Object> config) {
    }

    public record UpdateRequest(String name, String authType,
                                java.util.Map<String, Object> config, Boolean enabled) {
    }

    public record AuthorizeRequest(String secret) {
    }

    public record OAuthCallbackRequest(String code, String state) {
    }

    public record ConnectorView(String id, String tenantId, String name, String type,
                                String authType, java.util.Map<String, Object> config,
                                boolean enabled, boolean hasCredential, boolean inherited) {
    }

    public record InvokeRequest(String tenantId, String callId, String operation,
                                java.util.Map<String, Object> params, Integer timeoutMs,
                                String traceId) {
    }

    public record InvokeResponse(boolean ok, java.util.Map<String, Object> data,
                                 String errorCode, boolean transientError, long latencyMs) {
    }

    public record IdSummary(String id, String name, String tenantId) {
    }
}
