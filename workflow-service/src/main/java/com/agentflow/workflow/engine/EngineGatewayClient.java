package com.agentflow.workflow.engine;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.trace.TraceIdContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    /** 试跑要等上游节点真实跑完，读超时必须比普通调度请求长。 */
    private final RestClient slow;
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
        SimpleClientHttpRequestFactory slowFactory = new SimpleClientHttpRequestFactory();
        slowFactory.setConnectTimeout(2_000);
        slowFactory.setReadTimeout(150_000);
        this.slow = builder.baseUrl(gatewayBaseUrl + "/engine/internal")
                .requestFactory(slowFactory).build();
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

    /**
     * 模型清单。models 是纯 id 列表（下拉框用），items 是引擎附带的能力标注。
     *
     * items 用 Map 透传而不是在 Java 侧再定义一套能力模型：能力的语义归引擎所有
     * （哪里有字段、怎么推断），控制面只是把它递给界面，多定义一层只会让两边走偏。
     */
    public record LlmModelsResponse(boolean configured, String defaultModel,
                                    List<String> models, String error,
                                    List<Map<String, Object>> items) {
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

    /**
     * 单节点试跑：引擎裁剪上游后真实执行到目标节点并返回结果。
     *
     * <p>裁剪不了的结构（循环 / 并行 / 审批）在引擎侧是 422，这里把引擎给的原因原样
     * 传给前端——「为什么不能试跑」比「试跑失败」有用得多。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> previewNode(Map<String, Object> request) {
        try {
            Map<String, Object> body = slow.post().uri("/nodes/preview")
                    .headers(this::applyInternal)
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of() : body;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 422 || e.getStatusCode().value() == 400) {
                throw new BusinessException(ErrorCode.DSL_VALIDATION_001, engineMessage(e));
            }
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine preview failed");
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine unreachable");
        }
    }

    /** problem+json 里的可读原因；解析不出来时退回状态码，别把整段响应体塞给用户。 */
    private String engineMessage(RestClientResponseException e) {
        try {
            Map<String, Object> body = e.getResponseBodyAs(new org.springframework.core
                    .ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (body != null) {
                Object detail = body.get("detail");
                if (detail instanceof String text && !text.isBlank()) {
                    return text;
                }
                Object title = body.get("title");
                if (title instanceof String text && !text.isBlank()) {
                    return text;
                }
            }
        } catch (RuntimeException ignored) {
            // 响应体不是 problem+json：用状态码兜底
        }
        return "试跑请求被引擎拒绝（HTTP " + e.getStatusCode().value() + "）";
    }

    /**
     * AF-3：事件尾读 URI。spaceId 非空时把空间谓词下推到引擎，避免单次请求
     * 无条件读到全租户事件；为空时保留全局语义（系统级投影器，见 EventProjector）。
     * 包级可见，供契约测试断言下推参数不会随重构悄悄退化。
     */
    static String eventsUri(String spaceId) {
        return (spaceId == null || spaceId.isBlank())
                ? "/events?after_seq={a}&limit={l}"
                : "/events?after_seq={a}&limit={l}&spaceId={s}";
    }

    public EventsPage tailEvents(long afterSeq, int limit, String spaceId) {
        try {
            Map<String, Object> vars = new java.util.HashMap<>();
            vars.put("a", afterSeq);
            vars.put("l", limit);
            if (spaceId != null && !spaceId.isBlank()) {
                vars.put("s", spaceId);
            }
            return http.get()
                    .uri(eventsUri(spaceId), vars)
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
    public Map<String, Object> executionState(String executionId) {
        return findExecutionState(executionId).orElseThrow(
                () -> new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine state failed"));
    }

    /**
     * 执行在引擎侧的真实状态；{@code Optional.empty()} 表示引擎<strong>明确不认识</strong>这条执行
     * （从没收到过，或者事件已被清理）。
     *
     * <p>与 {@link #executionState} 的差别只在这一种情况：那个方法把 404 也当上游故障抛出，
     * 详情页据此返回 null 是够用的；看护（{@code ExecutionReaper}）需要区分「不认识」与「连不上」，
     * 因为处置不同——前者不会再有事件来了，后者可能只是抖动，得再看一轮。
     */
    public Optional<Map<String, Object>> findExecutionState(String executionId) {
        try {
            Map<String, Object> body = http.get()
                    .uri("/executions/{id}/state", executionId)
                    .headers(this::applyInternal)
                    .retrieve()
                    .body(Map.class);
            return Optional.ofNullable(body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine state failed");
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
