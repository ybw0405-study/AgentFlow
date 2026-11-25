package com.agentflow.connector.service;

import com.agentflow.common.tenant.TenantHierarchyResolver;
import com.agentflow.common.tenant.TenantTreePort;
import com.agentflow.connector.crypto.CredentialCipher;
import com.agentflow.connector.domain.ConnectorRepository;
import com.agentflow.connector.domain.ConnectorRepository.ConnectorRow;
import com.agentflow.connector.domain.ConnectorRepository.CredentialRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
import java.util.function.BiFunction;
import java.util.function.Function;
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
    private final TenantHierarchyResolver resolver;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER) // 重定向可绕过白名单，一律不跟
            .build();

    public ConnectorInvoker(ConnectorRepository repo, CredentialCipher cipher,
                            StringRedisTemplate redis, SandboxPolicy sandbox,
                            TenantTreePort treePort) {
        this.repo = repo;
        this.cipher = cipher;
        this.redis = redis;
        this.sandbox = sandbox;
        this.resolver = new TenantHierarchyResolver(treePort);
    }

    public record InvokeCommand(String tenantId, String connectorId, String callId,
                                String operation, Map<String, Object> params, int timeoutMs,
                                String traceId, String mcpMethod) {
        /** 非 MCP 调用与既有测试沿用 7 参形态；mcpMethod 缺省 null 即 tools/call。 */
        public InvokeCommand(String tenantId, String connectorId, String callId,
                             String operation, Map<String, Object> params, int timeoutMs,
                             String traceId) {
            this(tenantId, connectorId, callId, operation, params, timeoutMs, traceId, null);
        }
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

    /** MCP 工具清单探测结果。只回 name/description/inputSchema，端点与密钥不出本进程。 */
    public record McpToolsResult(boolean ok, List<McpProtocol.ToolItem> tools, String errorCode) {
        static McpToolsResult failure(String code) {
            return new McpToolsResult(false, List.of(), code);
        }
    }

    /** MCP 资源清单探测结果。只回定位与展示字段，内容本体走 resources/read。 */
    public record McpResourcesResult(boolean ok, List<McpProtocol.ResourceItem> resources,
                                     String errorCode) {
        static McpResourcesResult failure(String code) {
            return new McpResourcesResult(false, List.of(), code);
        }
    }

    /** MCP 提示清单探测结果。只回 name/description/arguments 摘要。 */
    public record McpPromptsResult(boolean ok, List<McpProtocol.PromptItem> prompts,
                                   String errorCode) {
        static McpPromptsResult failure(String code) {
            return new McpPromptsResult(false, List.of(), code);
        }
    }

    /** 一次 MCP 会话：sessionId 可为 null（无状态服务端），version 为协商后的协议版本。 */
    private record McpSession(String sessionId, String protocolVersion) {
    }

    private record McpHttpResponse(int status, String sessionId, JsonNode message) {
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
     * MCP 工具调用：initialize → tools/call，operation 即 MCP 工具名，params 即 arguments。
     *
     * <p>与 HTTP 连接器共用同一套沙箱、凭据注入、callId 幂等缓存与响应上限；
     * 区别只在于出网帧是 JSON-RPC 而非裸 REST。会话过期（带 session 的请求收到 404）
     * 时重建一次会话再重发，对引擎透明；其余失败按 CONN-* 家族映射瞬时/永久。
     */
    private InvokeResult mcpInvoke(InvokeCommand cmd, ConnectorRow connector, JsonNode config,
                                   long started, String cacheKey) {
        String method = cmd.mcpMethod() == null ? "tools/call" : cmd.mcpMethod();
        if (!MCP_METHODS.contains(method)) {
            return InvokeResult.failure("CONN-CONFIG-001", false, elapsed(started));
        }
        long deadline = started + Duration.ofMillis(Math.max(cmd.timeoutMs(), 1_000)).toNanos();
        try {
            SandboxPolicy.ValidatedTarget target = mcpTarget(connector, config);
            JsonNode samplingCfg = config.path("sampling");
            String samplingConnector = samplingCfg.path("connectorId").asText("");
            boolean samplingEnabled = !samplingConnector.isBlank();
            int[] samplingCount = {0};
            long[] samplingTokens = {0, 0};
            java.util.concurrent.atomic.AtomicReference<McpSession> sessionRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            McpProtocol.ServerRequestHandler handler = samplingHandler(
                    cmd, connector, config, target, samplingConnector, samplingEnabled,
                    samplingCfg, deadline, samplingCount, samplingTokens, sessionRef);
            sessionRef.set(mcpInitialize(connector, config, target, deadline, samplingEnabled));
            Map<String, Object> params = cmd.params() == null ? Map.of() : cmd.params();
            JsonNode callResult;
            try {
                callResult = mcpRequest(connector, config, target, sessionRef.get(),
                        mcpFrame(method, 2, cmd.operation(), params), 2, deadline, handler);
            } catch (McpProtocol.McpException e) {
                if (e.status != 404 || sessionRef.get().sessionId() == null) {
                    throw e;
                }
                sessionRef.set(mcpInitialize(connector, config, target, deadline, samplingEnabled));
                callResult = mcpRequest(connector, config, target, sessionRef.get(),
                        mcpFrame(method, 3, cmd.operation(), params), 3, deadline, handler);
            }
            Map<String, Object> data = switch (method) {
                case "resources/read" -> McpProtocol.readData(callResult);
                case "prompts/get" -> McpProtocol.promptData(callResult);
                default -> McpProtocol.callData(callResult);
            };
            if (samplingCount[0] > 0) {
                data.put("sampling", Map.of("requests", samplingCount[0],
                        "promptTokens", samplingTokens[0], "completionTokens", samplingTokens[1]));
            }
            if ("tools/call".equals(method) && callResult.path("isError").asBoolean(false)) {
                // 工具级失败是确定性结果：不缓存、不重试，但保留 content 供下游判读
                return new InvokeResult(false, data, "CONN-TOOL-001", false, elapsed(started), 200);
            }
            safeSet(cacheKey, JSON.writeValueAsString(data));
            return new InvokeResult(true, data, null, false, elapsed(started), 200);
        } catch (SandboxPolicy.Violation e) {
            log.warn("sandbox rejected mcp connector {}: {}", cmd.connectorId(), e.getMessage());
            return InvokeResult.failure("CONN-SANDBOX-001", false, elapsed(started));
        } catch (CredentialException e) {
            return InvokeResult.failure(e.code, false, elapsed(started));
        } catch (McpProtocol.McpException e) {
            return InvokeResult.failure(e.code, e.transientError, elapsed(started));
        } catch (HttpTimeoutException e) {
            return InvokeResult.failure("CONN-TIMEOUT", true, elapsed(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return InvokeResult.failure("CONN-TIMEOUT", true, elapsed(started));
        } catch (Exception e) {
            log.warn("mcp connector {} invoke failed: {}", cmd.connectorId(), e.toString());
            return InvokeResult.failure("CONN-UPSTREAM-001", true, elapsed(started));
        }
    }

    /**
     * 探测 MCP 服务端的工具清单，供前端建连接时挑选工具名（operation）。
     * 与 {@link #probeModels} 同口径：交互式操作不走 callId 缓存，分页与条数都有上限。
     */
    public McpToolsResult probeMcpTools(ConnectorRow connector, int timeoutMs) {
        return mcpProbe(connector, timeoutMs, deadline -> {
            McpSession session = mcpProbeSession(connector, deadline);
            return new McpToolsResult(true, List.copyOf(mcpList(connector, session, deadline,
                    McpProtocol::toolsList, McpProtocol::toolItems, "tools")), null);
        }, McpToolsResult::failure);
    }

    /** 资源清单探测：与工具清单同口径（分页/条数/截断），只回定位与展示字段。 */
    public McpResourcesResult probeMcpResources(ConnectorRow connector, int timeoutMs) {
        return mcpProbe(connector, timeoutMs, deadline -> {
            McpSession session = mcpProbeSession(connector, deadline);
            return new McpResourcesResult(true, List.copyOf(mcpList(connector, session, deadline,
                    McpProtocol::resourcesList, McpProtocol::resourceItems, "resources")), null);
        }, McpResourcesResult::failure);
    }

    /** 提示清单探测：arguments 只搬 name/description/required 摘要。 */
    public McpPromptsResult probeMcpPrompts(ConnectorRow connector, int timeoutMs) {
        return mcpProbe(connector, timeoutMs, deadline -> {
            McpSession session = mcpProbeSession(connector, deadline);
            return new McpPromptsResult(true, List.copyOf(mcpList(connector, session, deadline,
                    McpProtocol::promptsList, McpProtocol::promptItems, "prompts")), null);
        }, McpPromptsResult::failure);
    }

    private McpSession mcpProbeSession(ConnectorRow connector, long deadline)
            throws IOException, InterruptedException {
        SandboxPolicy.ValidatedTarget target = mcpTarget(connector, connector.config());
        return mcpInitialize(connector, connector.config(), target, deadline, false);
    }

    /** 三类清单共用的翻页骨架：5 页 / 200 条上限，cursor 空即止。 */
    private <T> List<T> mcpList(ConnectorRow connector, McpSession session, long deadline,
                                BiFunction<Integer, String, String> frame,
                                Function<JsonNode, List<T>> parser, String field)
            throws IOException, InterruptedException {
        SandboxPolicy.ValidatedTarget target = mcpTarget(connector, connector.config());
        List<T> items = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < McpProtocol.MAX_LIST_PAGES; page++) {
            JsonNode result = mcpRequest(connector, connector.config(), target, session,
                    frame.apply(10 + page, cursor), 10 + page, deadline, null);
            items.addAll(parser.apply(result.path(field)));
            cursor = result.path("nextCursor").asText("");
            if (cursor.isBlank() || items.size() >= McpProtocol.MAX_TOOLS) {
                break;
            }
        }
        return items.size() > McpProtocol.MAX_TOOLS
                ? items.subList(0, McpProtocol.MAX_TOOLS) : items;
    }

    @FunctionalInterface
    private interface ProbeAction<R> {
        R run(long deadline) throws Exception;
    }

    /** 三类探测共用的失败映射：沙箱/凭据/协议/超时/上游，与 invoke 同口径。 */
    private <R> R mcpProbe(ConnectorRow connector, int timeoutMs, ProbeAction<R> action,
                           Function<String, R> failure) {
        long deadline = System.nanoTime() + Duration.ofMillis(Math.max(timeoutMs, 1_000)).toNanos();
        try {
            return action.run(deadline);
        } catch (SandboxPolicy.Violation e) {
            log.warn("sandbox rejected mcp probe for {}: {}", connector.id(), e.getMessage());
            return failure.apply("CONN-SANDBOX-001");
        } catch (CredentialException e) {
            return failure.apply(e.code);
        } catch (McpProtocol.McpException e) {
            return failure.apply(e.code);
        } catch (HttpTimeoutException e) {
            return failure.apply("CONN-TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure.apply("CONN-TIMEOUT");
        } catch (Exception e) {
            log.warn("mcp probe for {} failed: {}", connector.id(), e.toString());
            return failure.apply("CONN-UPSTREAM-001");
        }
    }

    private static final Set<String> MCP_METHODS =
            Set.of("tools/call", "resources/read", "prompts/get");

    /** 按 mcpMethod 选帧；resources/prompts 的定位参数缺失视为配置错误（永久）。 */
    @SuppressWarnings("unchecked")
    private static String mcpFrame(String method, int id, String operation,
                                   Map<String, Object> params) {
        return switch (method) {
            case "resources/read" -> McpProtocol.resourcesRead(id, stringParam(params, "uri"));
            case "prompts/get" -> McpProtocol.promptsGet(id, stringParam(params, "name"),
                    params.get("arguments") instanceof Map
                            ? (Map<String, Object>) params.get("arguments") : Map.of());
            default -> McpProtocol.toolsCall(id, operation, params);
        };
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new McpProtocol.McpException("CONN-CONFIG-001", false);
        }
        return text;
    }

    /**
     * sampling 反向调用：服务端在 tools/call 的 SSE 流里发 sampling/createMessage，
     * 这里同步借租户自己的 LLM 连接器回答，再续读原响应。
     *
     * <p>硬上限：每调用请求数（默认 4、上限 8）、max_tokens（默认 1024、上限 4096）、
     * 共享 deadline。未配置/超帽/目标非 LLM 一律回 -32601 让服务端降级。
     * 嵌套调用复用 {@link #invoke}：缓存、NX 锁、沙箱、凭据、租户链校验全部同口径；
     * 子 callId 与父不同，不会自锁。成本不进引擎预算账本，靠上限兜底并回传计量元数据。
     */
    private McpProtocol.ServerRequestHandler samplingHandler(
            InvokeCommand cmd, ConnectorRow connector, JsonNode config,
            SandboxPolicy.ValidatedTarget target, String samplingConnector, boolean samplingEnabled,
            JsonNode samplingCfg, long deadline, int[] samplingCount, long[] samplingTokens,
            java.util.concurrent.atomic.AtomicReference<McpSession> sessionRef) {
        int maxRequests = Math.max(1, Math.min(samplingCfg.path("maxRequests").asInt(4), 8));
        int maxTokensCap = Math.max(1, Math.min(samplingCfg.path("maxTokens").asInt(1024), 4096));
        return request -> {
            int rid = request.path("id").asInt();
            McpSession session = sessionRef.get();
            String sid = session == null ? null : session.sessionId();
            String ver = session == null ? null : session.protocolVersion();
            if (!"sampling/createMessage".equals(request.path("method").asText())
                    || !samplingEnabled || samplingCount[0] >= maxRequests) {
                mcpPost(connector, config, target,
                        McpProtocol.errorResponse(rid, -32601, "sampling not available"),
                        sid, ver, 0, deadline, null);
                return;
            }
            ConnectorRow llm = repo.findById(samplingConnector).orElse(null);
            if (llm == null || !"LLM".equals(llm.type()) || !llm.enabled()) {
                mcpPost(connector, config, target,
                        McpProtocol.errorResponse(rid, -32601, "sampling connector unavailable"),
                        sid, ver, 0, deadline, null);
                return;
            }
            samplingCount[0]++;
            JsonNode params = request.path("params");
            List<Map<String, Object>> messages = new java.util.ArrayList<>();
            String system = params.path("systemPrompt").asText("");
            if (!system.isBlank()) {
                messages.add(Map.of("role", "system", "content", system));
            }
            for (JsonNode message : params.path("messages")) {
                String text = samplingText(message.path("content"));
                if (!text.isEmpty()) {
                    messages.add(Map.of("role", message.path("role").asText("user"),
                            "content", text));
                }
            }
            int maxTokens = Math.min(params.path("maxTokens").asInt(1024), maxTokensCap);
            Map<String, Object> payload = new LinkedHashMap<>();
            String model = llm.config().path("defaultModel").asText("");
            if (!model.isBlank()) {
                payload.put("model", model);
            }
            payload.put("messages", messages);
            payload.put("max_tokens", maxTokens);
            payload.put("stream", false);
            if (params.has("temperature")) {
                payload.put("temperature", params.path("temperature").asDouble());
            }
            long remaining = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            InvokeResult sampled = invoke(new InvokeCommand(connector.tenantId(),
                    samplingConnector, cmd.callId() + ":samp:" + samplingCount[0], "chat",
                    payload, (int) remaining, cmd.traceId()));
            if (!sampled.ok() || sampled.data() == null) {
                mcpPost(connector, config, target,
                        McpProtocol.errorResponse(rid, -32603,
                                sampled.errorCode() == null ? "sampling failed" : sampled.errorCode()),
                        sid, ver, 0, deadline, null);
                return;
            }
            JsonNode upstream = JSON.valueToTree(sampled.data());
            samplingTokens[0] += upstream.path("usage").path("prompt_tokens").asLong(0);
            samplingTokens[1] += upstream.path("usage").path("completion_tokens").asLong(0);
            JsonNode content = upstream.path("choices").path(0).path("message").path("content");
            String text = content.isTextual() ? content.asText() : McpProtocol.joinText(content);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("role", "assistant");
            result.put("content", Map.of("type", "text", "text", text));
            result.put("model", upstream.path("model").asText(model));
            result.put("stopReason", "endTurn");
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("jsonrpc", "2.0");
            reply.put("id", rid);
            reply.put("result", result);
            mcpPost(connector, config, target, JSON.writeValueAsString(reply), sid, ver, 0,
                    deadline, null);
        };
    }

    /** sampling 消息内容：text part 直取，resource part 内联文本；image/audio 跳过（text-only）。 */
    private static String samplingText(JsonNode content) {
        StringBuilder text = new StringBuilder();
        Iterable<JsonNode> parts = content.isArray() ? content : List.of(content);
        for (JsonNode part : parts) {
            String type = part.path("type").asText("");
            String value = "text".equals(type) ? part.path("text").asText("")
                    : "resource".equals(type) ? part.path("resource").path("text").asText("") : "";
            if (value.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(value);
        }
        return text.toString();
    }

    /** MCP 单端点：baseUrl 即 JSON-RPC 端点，不做 operations 路径拼接。 */
    private SandboxPolicy.ValidatedTarget mcpTarget(ConnectorRow connector, JsonNode config) {
        String baseUrl = config.path("baseUrl").asText("");
        if (baseUrl.isBlank()) {
            throw new CredentialException("CONN-CONFIG-001", "baseUrl missing");
        }
        List<String> hosts = JSON.convertValue(config.path("allowedHosts"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        SandboxPolicy.ValidatedTarget target =
                sandbox.validateWithDns(baseUrl.replaceAll("/+$", ""), hosts);
        sandbox.assertNoRebinding(target.uri().getHost(), target.resolved());
        return target;
    }

    private McpSession mcpInitialize(ConnectorRow connector, JsonNode config,
                                     SandboxPolicy.ValidatedTarget target, long deadline,
                                     boolean sampling)
            throws IOException, InterruptedException {
        McpHttpResponse response = mcpPost(connector, config, target,
                McpProtocol.initialize(1, sampling), null, null, 1, deadline, null);
        JsonNode result = McpProtocol.result(response.message(), 1);
        String version = McpProtocol.negotiatedVersion(result);
        String sessionId = response.sessionId();
        mcpPost(connector, config, target, McpProtocol.initialized(), sessionId, version, 0,
                deadline, null);
        return new McpSession(sessionId, version);
    }

    private JsonNode mcpRequest(ConnectorRow connector, JsonNode config,
                                SandboxPolicy.ValidatedTarget target, McpSession session,
                                String body, int id, long deadline,
                                McpProtocol.ServerRequestHandler handler)
            throws IOException, InterruptedException {
        McpHttpResponse response = mcpPost(connector, config, target, body,
                session.sessionId(), session.protocolVersion(), id, deadline, handler);
        return McpProtocol.result(response.message(), id);
    }

    /**
     * 一次 JSON-RPC POST。响应可能是 JSON 或 SSE；202/204 无体（通知）；
     * 3xx 不跟随；404 携带状态码抛出供会话过期判定。整段请求受 deadline 约束，
     * 多次往返共享同一预算，不会把引擎侧 timeoutMs+5s 的客户端超时撑破。
     */
    private McpHttpResponse mcpPost(ConnectorRow connector, JsonNode config,
                                    SandboxPolicy.ValidatedTarget target, String body,
                                    String sessionId, String protocolVersion, int id, long deadline,
                                    McpProtocol.ServerRequestHandler handler)
            throws IOException, InterruptedException {
        // 未配置 sampling 时收到反向请求：回 -32601 让服务端降级，而不是挂住流
        McpProtocol.ServerRequestHandler effective = handler != null ? handler : request ->
                mcpPost(connector, config, target,
                        McpProtocol.errorResponse(request.path("id").asInt(), -32601,
                                "method not supported"),
                        sessionId, protocolVersion, 0, deadline, null);
        long remainingMs = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target.uri())
                .timeout(Duration.ofMillis(remainingMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        applyAuth(builder, connector, config, connector.tenantId());
        config.path("headers").properties().forEach(e ->
                builder.header(e.getKey(), e.getValue().asText()));

        HttpResponse<InputStream> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        String newSession = response.headers().firstValue("Mcp-Session-Id").orElse(sessionId);
        try (InputStream stream = response.body()) {
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                throw new McpProtocol.McpException("CONN-REDIRECT-001", false, status);
            }
            if (status == 202 || status == 204) {
                return new McpHttpResponse(status, newSession, null);
            }
            if (status >= 400) {
                boolean transientError = status >= 500 || status == 429;
                throw new McpProtocol.McpException(
                        transientError ? "CONN-HTTP-5XX" : "CONN-HTTP-4XX", transientError, status);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            JsonNode message = null;
            if (contentType.startsWith("text/event-stream")) {
                if (id > 0) {
                    message = McpProtocol.sseResponse(stream, id, MAX_RESPONSE_BYTES,
                            Duration.ofMillis(remainingMs), effective);
                }
            } else {
                byte[] bytes = BoundedBodyReader.read(stream, MAX_RESPONSE_BYTES,
                        Duration.ofMillis(remainingMs));
                if (bytes == null) {
                    throw new McpProtocol.McpException("CONN-RESPONSE-TOO_LARGE", false);
                }
                if (bytes.length > 0) {
                    message = McpProtocol.parse(bytes);
                }
            }
            return new McpHttpResponse(status, newSession, message);
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
        // 纵深防御：即便调用方绕过 controller 直接构造 InvokeCommand，
        // 也不能凭裸 id 调用不属于自己可见租户链的连接器。沿用与 visibleEnabled
        // 相同的 chainOf（含祖先共享），因此子租户调用祖先连接器仍合法。
        if (!resolver.chainOf(cmd.tenantId()).stream()
                .anyMatch(n -> n.id().equals(connector.tenantId()))) {
            return InvokeResult.failure("CONN-NOT_FOUND-001", false, elapsed(started));
        }
        if (!connector.enabled()) {
            return InvokeResult.failure("CONN-DISABLED-001", false, elapsed(started));
        }

        JsonNode config = connector.config();
        if ("MCP".equals(connector.type())) {
            return mcpInvoke(cmd, connector, config, started, cacheKey);
        }
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
            // 过期且有 refresh_token 时透明刷新，避免调用方因 access token 过期直接失败
            JsonNode token = resolveOauthToken(connector, config, credential, aad);
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
     * 解析 OAUTH2 access token。过期时若有 refresh_token 则透明刷新并写回，
     * 否则抛过期错误；未过期或无过期时间直接返回。
     */
    private JsonNode resolveOauthToken(ConnectorRow connector, JsonNode config,
                                       CredentialRow credential, byte[] aad) {
        JsonNode token;
        try {
            token = JSON.readTree(cipher.open(credential.oauthToken(), aad,
                    credential.keyId() == null ? cipher.keyId() : credential.keyId()));
        } catch (Exception e) {
            throw new CredentialException("CONN-CREDENTIAL-002", "oauth token unreadable");
        }
        long expiresAt = token.path("expiresAt").asLong(0);
        if (expiresAt <= 0 || expiresAt >= System.currentTimeMillis() / 1000) {
            return token;
        }
        String refreshToken = token.path("refreshToken").asText("");
        if (refreshToken.isBlank()) {
            throw new CredentialException("CONN-CREDENTIAL-EXPIRED", "oauth token expired");
        }
        return refreshOauthToken(connector, config, credential, aad, refreshToken);
    }

    /**
     * 用 refresh_token 换新 access_token 并写回凭据。服务端可能轮换 refresh_token，
     * 一并落库；刷新失败按过期处理（重试无益，需管理员重新授权）。
     */
    private JsonNode refreshOauthToken(ConnectorRow connector, JsonNode config,
                                       CredentialRow credential, byte[] aad,
                                       String refreshToken) {
        JsonNode oauth = config.path("oauth");
        String tokenUrl = oauth.path("tokenUrl").asText("");
        if (tokenUrl.isBlank()) {
            throw new CredentialException("CONN-CREDENTIAL-001", "oauth.tokenUrl required");
        }
        URI uri = sandbox.validate(tokenUrl, oauthHosts(config));
        if (credential.secret() == null) {
            throw new CredentialException("CONN-CREDENTIAL-001", "oauth client secret not configured");
        }
        String clientSecret = new String(cipher.open(credential.secret(), aad,
                credential.keyId() == null ? cipher.keyId() : credential.keyId()),
                StandardCharsets.UTF_8);
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(oauth.path("clientId").asText(""),
                        StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] payload;
            try (InputStream stream = response.body()) {
                payload = BoundedBodyReader.read(stream, MAX_RESPONSE_BYTES, Duration.ofSeconds(10));
            }
            if (payload == null || response.statusCode() >= 400) {
                throw new CredentialException("CONN-CREDENTIAL-EXPIRED", "oauth refresh failed");
            }
            JsonNode refreshed = JSON.readTree(payload);
            String accessToken = refreshed.path("access_token").asText("");
            if (accessToken.isBlank()) {
                throw new CredentialException("CONN-CREDENTIAL-EXPIRED",
                        "oauth refresh returned no access_token");
            }
            long expiresIn = refreshed.path("expires_in").asLong(0);
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("accessToken", accessToken);
            stored.put("tokenType", refreshed.path("token_type").asText("Bearer"));
            stored.put("scope", refreshed.path("scope").asText(""));
            String rotated = refreshed.path("refresh_token").asText(refreshToken);
            if (!rotated.isBlank()) {
                stored.put("refreshToken", rotated);
            }
            stored.put("expiresAt", expiresIn > 0
                    ? System.currentTimeMillis() / 1000 + expiresIn : 0);
            byte[] sealed = cipher.seal(
                    JSON.writeValueAsString(stored).getBytes(StandardCharsets.UTF_8), aad);
            repo.upsertCredential(UUID.randomUUID().toString(), connector.id(), connector.tenantId(),
                    credential.secret(), sealed, cipher.keyId(),
                    expiresIn > 0 ? new Timestamp(System.currentTimeMillis() + expiresIn * 1000) : null,
                    stored.get("scope").toString());
            return JSON.valueToTree(stored);
        } catch (CredentialException e) {
            throw e;
        } catch (Exception e) {
            log.warn("oauth refresh failed for connector {}: {}", connector.id(), e.toString());
            throw new CredentialException("CONN-CREDENTIAL-EXPIRED", "oauth refresh failed");
        }
    }

    /** oauth.allowedHosts 优先，缺省回退顶层 allowedHosts。 */
    private List<String> oauthHosts(JsonNode config) {
        JsonNode oauth = config.path("oauth");
        JsonNode node = oauth.path("allowedHosts").isMissingNode()
                ? config.path("allowedHosts") : oauth.path("allowedHosts");
        return JSON.convertValue(node,
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
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
