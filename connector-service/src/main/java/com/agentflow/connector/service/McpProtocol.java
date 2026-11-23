package com.agentflow.connector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP Streamable HTTP 的协议帧：JSON-RPC 2.0 请求构造、响应判定与 SSE 解帧。
 *
 * <p>只做协议 mechanics，不持有连接与凭据——出网、沙箱、凭据注入都在
 * {@link ConnectorInvoker}，与 HTTP/LLM 连接器完全同一口径。刻意不引官方 SDK：
 * 客户端侧只需要 initialize / tools/list / tools/call 三个方法，手写帧可审计，
 * 也避免把 reactor 等传递依赖带进本模块。
 *
 * <p>不实现 stdio 传输：拉起本地进程超出出网沙箱的治理面。远端 MCP 服务一律
 * 走 Streamable HTTP 单端点 POST，响应可能是 {@code application/json} 或
 * {@code text/event-stream}，两种都认。
 */
final class McpProtocol {

    /** 客户端声明的最新版本；服务端可协商回退到任一已知版本。 */
    static final String PREFERRED_VERSION = "2025-06-18";
    static final Set<String> SUPPORTED_VERSIONS = Set.of("2025-03-26", PREFERRED_VERSION);
    static final int MAX_TOOLS = 200;
    static final int MAX_TOOL_NAME_LENGTH = 200;
    static final int MAX_DESCRIPTION_LENGTH = 500;
    /** 提示参数列表只用于展示与填参提示，截断到前 16 项。 */
    static final int MAX_FIELD_ENTRIES = 16;
    /** tools/list 分页上限：交互探测不该被上游无限翻页拖住。 */
    static final int MAX_LIST_PAGES = 5;

    private static final ObjectMapper JSON = new ObjectMapper();

    private McpProtocol() {
    }

    /** 协议层失败：code 沿用 CONN-* 家族，transientError 决定引擎是否退避重试。 */
    static final class McpException extends RuntimeException {
        final String code;
        final boolean transientError;
        /** 上游 HTTP 状态码（非 HTTP 来源的失败为 0），用于会话过期判定。 */
        final int status;

        McpException(String code, boolean transientError) {
            this(code, transientError, 0);
        }

        McpException(String code, boolean transientError, int status) {
            super(code);
            this.code = code;
            this.transientError = transientError;
            this.status = status;
        }
    }

    static String initialize(int id) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PREFERRED_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "agentflow-connector", "version", "1.0.0"));
        return request(id, "initialize", params);
    }

    static String initialized() {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    }

    static String toolsList(int id, String cursor) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (cursor != null && !cursor.isBlank()) {
            params.put("cursor", cursor);
        }
        return request(id, "tools/list", params);
    }

    static String toolsCall(int id, String tool, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        return request(id, "tools/call", params);
    }

    static String resourcesList(int id, String cursor) {
        return listRequest(id, "resources/list", cursor);
    }

    static String promptsList(int id, String cursor) {
        return listRequest(id, "prompts/list", cursor);
    }

    static String resourcesRead(int id, String uri) {
        return request(id, "resources/read", Map.of("uri", uri));
    }

    static String promptsGet(int id, String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        return request(id, "prompts/get", params);
    }

    private static String listRequest(int id, String method, String cursor) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (cursor != null && !cursor.isBlank()) {
            params.put("cursor", cursor);
        }
        return request(id, method, params);
    }

    private static String request(int id, String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        try {
            return JSON.writeValueAsString(message);
        } catch (Exception e) {
            throw new McpException("CONN-CONFIG-001", false);
        }
    }

    static JsonNode parse(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (!node.isObject()) {
                throw new McpException("CONN-MCP-001", false);
            }
            return node;
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("CONN-MCP-001", false);
        }
    }

    /**
     * 判定一条 JSON-RPC 响应并取 {@code result}。
     *
     * <p>error 对象映射为 {@code CONN-MCP-002}：仅内部错误（-32603）视为瞬时，
     * 参数/方法类错误重试无益。id 不匹配或既无 result 也无 error 视为协议损坏。
     */
    static JsonNode result(JsonNode message, int id) {
        if (message == null || !"2.0".equals(message.path("jsonrpc").asText())
                || message.path("id").asInt(-1) != id) {
            throw new McpException("CONN-MCP-001", false);
        }
        JsonNode error = message.path("error");
        if (error.isObject()) {
            int code = error.path("code").asInt(0);
            throw new McpException("CONN-MCP-002", code == -32603);
        }
        JsonNode result = message.path("result");
        if (!result.isObject()) {
            throw new McpException("CONN-MCP-001", false);
        }
        return result;
    }

    /** 协商版本必须落在已知集合内，否则按协议损坏拒绝（不猜测未知版本语义）。 */
    static String negotiatedVersion(JsonNode initializeResult) {
        String version = initializeResult.path("protocolVersion").asText("");
        if (!SUPPORTED_VERSIONS.contains(version)) {
            throw new McpException("CONN-MCP-001", false);
        }
        return version;
    }

    /**
     * 从 SSE 流里取出与 {@code id} 匹配的响应帧。
     *
     * <p>逐行读而非整段读：服务端可能保持流打开，等到 EOF 会超时。按 SSE 语义
     * 空行分帧、{@code data:} 行拼接，忽略注释与服务端主动通知；字节总量与
     * 总时长两条上限与 {@link BoundedBodyReader} 同口径。
     */
    static JsonNode sseResponse(InputStream in, int id, int maxBytes, Duration budget)
            throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + budget.toNanos();
        StringBuilder data = new StringBuilder();
        long total = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (System.nanoTime() > deadline) {
                throw new HttpTimeoutException("mcp sse read timed out");
            }
            total += line.length() + 1;
            if (total > maxBytes) {
                throw new McpException("CONN-RESPONSE-TOO_LARGE", false);
            }
            if (line.isEmpty()) {
                JsonNode message = matchFrame(data, id);
                data.setLength(0);
                if (message != null) {
                    return message;
                }
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            }
        }
        JsonNode message = matchFrame(data, id);
        if (message != null) {
            return message;
        }
        throw new McpException("CONN-MCP-001", false);
    }

    private static JsonNode matchFrame(StringBuilder data, int id) {
        if (data.length() == 0) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(data.toString());
            if (node.isObject() && node.path("id").asInt(-1) == id
                    && (node.has("result") || node.has("error"))) {
                return node;
            }
        } catch (Exception ignored) {
            // 非 JSON 的 data 帧（心跳等）直接跳过
        }
        return null;
    }

    /** 探测清单里的单个工具：只搬 name/description/inputSchema，其余字段不入库不展示。 */
    record ToolItem(String name, String description, Map<String, Object> inputSchema) {
    }

    static List<ToolItem> toolItems(JsonNode tools) {
        List<ToolItem> items = new ArrayList<>();
        if (!tools.isArray()) {
            return items;
        }
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String description = tool.path("description").asText("");
            Map<String, Object> schema = tool.path("inputSchema").isObject()
                    ? JSON.convertValue(tool.path("inputSchema"), Map.class) : Map.of();
            items.add(new ToolItem(truncate(name, MAX_TOOL_NAME_LENGTH),
                    truncate(description, MAX_DESCRIPTION_LENGTH), schema));
            if (items.size() >= MAX_TOOLS) {
                break;
            }
        }
        return items;
    }

    /** tools/call 的 result 原样转 Map（content/isError/structuredContent），供工作流下游消费。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> callData(JsonNode result) {
        return JSON.convertValue(result, Map.class);
    }

    /** 资源清单条目：只搬定位与展示字段，内容本体走 resources/read。 */
    record ResourceItem(String uri, String name, String description, String mimeType) {
    }

    /** 提示清单条目：arguments 只搬 name/description/required，schema 细节不入库。 */
    record PromptArgument(String name, String description, boolean required) {
    }

    record PromptItem(String name, String description, List<PromptArgument> arguments) {
    }

    static List<ResourceItem> resourceItems(JsonNode resources) {
        List<ResourceItem> items = new ArrayList<>();
        if (!resources.isArray()) {
            return items;
        }
        for (JsonNode resource : resources) {
            String uri = resource.path("uri").asText("");
            if (uri.isBlank()) {
                continue;
            }
            items.add(new ResourceItem(truncate(uri, MAX_TOOL_NAME_LENGTH),
                    truncate(resource.path("name").asText(""), MAX_TOOL_NAME_LENGTH),
                    truncate(resource.path("description").asText(""), MAX_DESCRIPTION_LENGTH),
                    truncate(resource.path("mimeType").asText(""), MAX_TOOL_NAME_LENGTH)));
            if (items.size() >= MAX_TOOLS) {
                break;
            }
        }
        return items;
    }

    static List<PromptItem> promptItems(JsonNode prompts) {
        List<PromptItem> items = new ArrayList<>();
        if (!prompts.isArray()) {
            return items;
        }
        for (JsonNode prompt : prompts) {
            String name = prompt.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            List<PromptArgument> arguments = new ArrayList<>();
            for (JsonNode argument : prompt.path("arguments")) {
                String argName = argument.path("name").asText("");
                if (argName.isBlank()) {
                    continue;
                }
                arguments.add(new PromptArgument(truncate(argName, MAX_TOOL_NAME_LENGTH),
                        truncate(argument.path("description").asText(""), MAX_DESCRIPTION_LENGTH),
                        argument.path("required").asBoolean(false)));
                if (arguments.size() >= MAX_FIELD_ENTRIES) {
                    break;
                }
            }
            items.add(new PromptItem(truncate(name, MAX_TOOL_NAME_LENGTH),
                    truncate(prompt.path("description").asText(""), MAX_DESCRIPTION_LENGTH),
                    List.copyOf(arguments)));
            if (items.size() >= MAX_TOOLS) {
                break;
            }
        }
        return items;
    }

    /** resources/read 的 result：contents 原样保留，另拼一份纯文本供下游直接引用。 */
    static Map<String, Object> readData(JsonNode result) {
        Map<String, Object> data = callData(result);
        data.put("text", joinText(result.path("contents")));
        return data;
    }

    /** prompts/get 的 result：messages 原样保留，另拼一份纯文本（角色前缀便于喂 llm 节点）。 */
    static Map<String, Object> promptData(JsonNode result) {
        Map<String, Object> data = callData(result);
        StringBuilder text = new StringBuilder();
        for (JsonNode message : result.path("messages")) {
            String role = message.path("role").asText("");
            String body = joinText(message.path("content").isArray()
                    ? message.path("content") : JSON.createArrayNode().add(message.path("content")));
            if (body.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(role).append(": ").append(body);
        }
        data.put("text", text.toString());
        return data;
    }

    /** 拼接 content/contents 数组里的 text part；blob/image 等非文本 part 不进 text。 */
    private static String joinText(JsonNode parts) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            if (!"text".equals(part.path("type").asText())) {
                continue;
            }
            String value = part.path("text").asText("");
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

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
