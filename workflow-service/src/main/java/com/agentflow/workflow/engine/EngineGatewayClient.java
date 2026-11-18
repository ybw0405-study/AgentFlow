package com.agentflow.workflow.engine;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.trace.TraceIdContext;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 对引擎的唯一出口（ArchUnit 保护）：workflow-service → gateway /engine/** → engine-api。
 * 内部令牌 + trace 透传；超时/熔断集中在网关路由。
 */
@Component
public class EngineGatewayClient {

    private final RestClient http;
    private final RestClient control;
    private final String internalToken;

    public EngineGatewayClient(
            @Value("${agentflow.gateway.base-url}") String gatewayBaseUrl,
            @Value("${agentflow.gateway.internal-token}") String internalToken,
            RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(15_000);
        this.http = builder.baseUrl(gatewayBaseUrl + "/engine/internal")
                .requestFactory(factory).build();
        this.control = builder.baseUrl(gatewayBaseUrl).requestFactory(factory).build();
        this.internalToken = internalToken;
    }

    public record DslErrorItem(String code, String message, String nodeKey, String path) {
    }

    public record DslValidationResponse(boolean ok, List<DslErrorItem> errors,
                                        Map<String, Object> normalizedDsl, String checksum) {
    }

    public record EngineEvent(long seq, String executionId, String eventType, String nodeKey,
                              Map<String, Object> payload, int aggregateVersion,
                              String createdAt) {
    }

    public record EventsPage(List<EngineEvent> events, long lastSeq) {
    }

    public record LlmModelsResponse(boolean configured, String defaultModel,
                                    List<String> models, String error) {
    }

    public DslValidationResponse validateDsl(Map<String, Object> dsl) {
        try {
            return http.post().uri("/dsl/validate")
                    .headers(this::applyInternal)
                    .body(Map.of("dsl", dsl))
                    .retrieve()
                    .body(DslValidationResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine unreachable");
        }
    }

    public Map<String, Object> submitExecution(Map<String, Object> request) {
        try {
            return http.post().uri("/executions")
                    .headers(this::applyInternal)
                    .body(request)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {
                    });
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine dispatch failed");
        }
    }

    public EventsPage tailEvents(long afterSeq, int limit) {
        try {
            return http.get()
                    .uri("/events?after_seq={a}&limit={l}", afterSeq, limit)
                    .headers(this::applyInternal)
                    .retrieve()
                    .body(EventsPage.class);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine tail failed");
        }
    }

    public EventsPage executionEvents(String executionId, long afterSeq, int limit) {
        try {
            return http.get()
                    .uri("/executions/{id}/events?after_seq={a}&limit={l}",
                            executionId, afterSeq, limit)
                    .headers(this::applyInternal)
                    .retrieve()
                    .body(EventsPage.class);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine events failed");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> nodeIo(String executionId, String nodeKey) {
        try {
            return http.get()
                    .uri("/executions/{id}/io/{key}", executionId, nodeKey)
                    .headers(this::applyInternal)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine io failed");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> cancelExecution(String executionId) {
        try {
            return http.post().uri("/executions/{id}/cancel", executionId)
                    .headers(this::applyInternal)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine cancel failed");
        }
    }

    /** 执行当前状态与 result（评测取 actual 用）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executionState(String executionId) {
        try {
            Map<String, Object> body = http.get()
                    .uri("/executions/{id}/state", executionId)
                    .headers(this::applyInternal)
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of() : body;
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine state failed");
        }
    }

    /** 可用 LLM 模型清单（画布下拉用）。引擎未配置真实端点时 configured=false。 */
    public LlmModelsResponse listLlmModels() {
        try {
            return http.get()
                    .uri("/llm/models")
                    .headers(this::applyInternal)
                    .retrieve()
                    .body(LlmModelsResponse.class);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine models failed");
        }
    }

    /**
     * 人工审批裁决（P3）。语义映射：
     * 404（gate 未挂起/已裁决）→ 409 WF-STATE-001；409（正在被裁决）→ 409；
     * 其它 4xx/网络/5xx → AGT-UPSTREAM-001（调用方据此落 outbox）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveGate(String executionId, String nodeKey, String decision,
                                           String resolvedBy, String comment) {
        try {
            Map<String, Object> body = http.post()
                    .uri("/executions/{id}/gates/{key}/resolve", executionId, nodeKey)
                    .headers(this::applyInternal)
                    .body(Map.of("decision", decision, "resolvedBy", resolvedBy,
                            "comment", comment == null ? "" : comment))
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of() : body;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 409) {
                throw new BusinessException(ErrorCode.WF_STATE_001,
                        "gate is no longer pending");
            }
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine gate resolve failed");
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine unreachable");
        }
    }

    /**
     * 该空间可见且已启用的连接器 id（含祖先租户共享）。连接器服务不可用时降级为
     * 空集合：mock 工作流仍可执行，真实连接器的节点会在授权校验处报 CONN-FORBIDDEN。
     */
    @SuppressWarnings("unchecked")
    public List<String> connectorIds(String spaceId) {
        try {
            Map<String, Object> body = control.get()
                    .uri("/internal/connectors?spaceId={s}", spaceId)
                    .headers(this::applyInternal)
                    .retrieve()
                    .body(Map.class);
            if (body == null || !(body.get("data") instanceof List<?> rows)) {
                return List.of();
            }
            return rows.stream()
                    .filter(Map.class::isInstance)
                    .map(row -> String.valueOf(((Map<String, Object>) row).get("id")))
                    .toList();
        } catch (RestClientException e) {
            return List.of();
        }
    }

    private void applyInternal(org.springframework.http.HttpHeaders h) {
        h.set("X-Internal-Token", internalToken);
        h.set("traceparent", TraceIdContext.newTraceparent(TraceIdContext.currentOrCreate()));
    }
}
