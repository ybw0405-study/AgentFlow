package com.agentflow.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MCP 协议帧：构造、响应判定、SSE 解帧、清单截断。 */
class McpProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Test
    void initializeCarriesPreferredVersionAndClientInfo() throws Exception {
        JsonNode request = json(McpProtocol.initialize(1));
        assertThat(request.path("method").asText()).isEqualTo("initialize");
        assertThat(request.path("id").asInt()).isEqualTo(1);
        assertThat(request.path("params").path("protocolVersion").asText())
                .isEqualTo(McpProtocol.PREFERRED_VERSION);
        assertThat(request.path("params").path("clientInfo").path("name").asText())
                .isEqualTo("agentflow-connector");
    }

    @Test
    void initializedIsNotificationWithoutId() throws Exception {
        JsonNode request = json(McpProtocol.initialized());
        assertThat(request.has("id")).isFalse();
        assertThat(request.path("method").asText()).isEqualTo("notifications/initialized");
    }

    @Test
    void toolsCallCarriesNameAndArguments() throws Exception {
        JsonNode request = json(McpProtocol.toolsCall(7, "crm-search", Map.of("q", "acme")));
        assertThat(request.path("params").path("name").asText()).isEqualTo("crm-search");
        assertThat(request.path("params").path("arguments").path("q").asText()).isEqualTo("acme");
    }

    @Test
    void resultUnwrapsMatchingResponse() throws Exception {
        JsonNode result = McpProtocol.result(
                json("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[]}}"), 2);
        assertThat(result.has("content")).isTrue();
    }

    @Test
    void mismatchedIdIsProtocolFailure() {
        assertThatThrownBy(() -> McpProtocol.result(
                json("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}"), 2))
                .isInstanceOf(McpProtocol.McpException.class)
                .extracting(e -> ((McpProtocol.McpException) e).code)
                .isEqualTo("CONN-MCP-001");
    }

    @Test
    void invalidParamsErrorIsPermanentButInternalErrorIsTransient() {
        assertThatThrownBy(() -> McpProtocol.result(
                json("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602}}"), 2))
                .isInstanceOf(McpProtocol.McpException.class)
                .satisfies(e -> {
                    McpProtocol.McpException m = (McpProtocol.McpException) e;
                    assertThat(m.code).isEqualTo("CONN-MCP-002");
                    assertThat(m.transientError).isFalse();
                });
        assertThatThrownBy(() -> McpProtocol.result(
                json("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32603}}"), 2))
                .isInstanceOf(McpProtocol.McpException.class)
                .satisfies(e -> assertThat(((McpProtocol.McpException) e).transientError).isTrue());
    }

    @Test
    void unknownProtocolVersionIsRejected() throws Exception {
        assertThatThrownBy(() -> McpProtocol.negotiatedVersion(
                json("{\"protocolVersion\":\"1999-01-01\"}")))
                .isInstanceOf(McpProtocol.McpException.class);
        assertThat(McpProtocol.negotiatedVersion(json("{\"protocolVersion\":\"2025-03-26\"}")))
                .isEqualTo("2025-03-26");
    }

    @Test
    void sseResponseSkipsHeartbeatsAndMatchesId() throws Exception {
        String stream = ": keep-alive\n\n"
                + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                + "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":2,\n"
                + "data: \"result\":{\"content\":[{\"type\":\"text\",\"text\":\"pong\"}]}}\n\n";
        JsonNode message = McpProtocol.sseResponse(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                2, 256 * 1024, Duration.ofSeconds(5));
        assertThat(McpProtocol.result(message, 2).path("content").get(0).path("text").asText())
                .isEqualTo("pong");
    }

    @Test
    void sseWithoutMatchingResponseIsProtocolFailure() {
        String stream = "data: {\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}\n\n";
        assertThatThrownBy(() -> McpProtocol.sseResponse(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                2, 256 * 1024, Duration.ofSeconds(5)))
                .isInstanceOf(McpProtocol.McpException.class);
    }

    @Test
    void sseOverByteCapIsRejected() {
        String stream = "data: " + "x".repeat(2048) + "\n\n";
        assertThatThrownBy(() -> McpProtocol.sseResponse(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                2, 1024, Duration.ofSeconds(5)))
                .isInstanceOf(McpProtocol.McpException.class)
                .extracting(e -> ((McpProtocol.McpException) e).code)
                .isEqualTo("CONN-RESPONSE-TOO_LARGE");
    }

    @Test
    void toolItemsTruncateAndDropNameless() throws Exception {
        JsonNode tools = json("[{\"name\":\"ok\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}},"
                + "{\"description\":\"no name\"},"
                + "{\"name\":\"" + "n".repeat(300) + "\",\"description\":\"" + "d".repeat(900) + "\"}]");
        List<McpProtocol.ToolItem> items = McpProtocol.toolItems(tools);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).inputSchema()).containsEntry("type", "object");
        assertThat(items.get(1).name()).hasSize(McpProtocol.MAX_TOOL_NAME_LENGTH);
        assertThat(items.get(1).description()).hasSize(McpProtocol.MAX_DESCRIPTION_LENGTH);
    }

    @Test
    void callDataPreservesIsErrorAndContent() throws Exception {
        Map<String, Object> data = McpProtocol.callData(
                json("{\"content\":[{\"type\":\"text\",\"text\":\"denied\"}],\"isError\":true}"));
        assertThat(data).containsEntry("isError", true);
        assertThat(data).containsKey("content");
    }

    @Test
    void resourceAndPromptFramesCarryLocatorParams() throws Exception {
        assertThat(json(McpProtocol.resourcesRead(4, "test://doc")).path("params").path("uri").asText())
                .isEqualTo("test://doc");
        JsonNode prompt = json(McpProtocol.promptsGet(5, "greet", Map.of("who", "acme")));
        assertThat(prompt.path("method").asText()).isEqualTo("prompts/get");
        assertThat(prompt.path("params").path("arguments").path("who").asText()).isEqualTo("acme");
        assertThat(json(McpProtocol.resourcesList(6, "c1")).path("params").path("cursor").asText())
                .isEqualTo("c1");
        assertThat(json(McpProtocol.promptsList(7, null)).path("params").has("cursor")).isFalse();
    }

    @Test
    void readDataJoinsTextPartsAndSkipsBlob() throws Exception {
        Map<String, Object> data = McpProtocol.readData(json(
                "{\"contents\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"blob\",\"blob\":\"xx\"},"
                        + "{\"type\":\"text\",\"text\":\"b\"}]}"));
        assertThat(data).containsEntry("text", "a\nb");
        assertThat(data).containsKey("contents");
    }

    @Test
    void promptDataJoinsMessagesWithRolePrefix() throws Exception {
        Map<String, Object> data = McpProtocol.promptData(json(
                "{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"q\"}},"
                        + "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}]}"));
        assertThat(data).containsEntry("text", "user: q\nassistant: a");
        assertThat(data).containsKey("messages");
    }

    @Test
    void resourceAndPromptItemsTruncateAndDropNameless() throws Exception {
        List<McpProtocol.ResourceItem> resources = McpProtocol.resourceItems(json(
                "[{\"uri\":\"" + "u".repeat(300) + "\"},{\"name\":\"no uri\"}]"));
        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).uri()).hasSize(McpProtocol.MAX_TOOL_NAME_LENGTH);

        List<McpProtocol.PromptItem> prompts = McpProtocol.promptItems(json(
                "[{\"name\":\"p\",\"arguments\":[{\"name\":\"a\",\"required\":true},{\"description\":\"x\"}]},"
                        + "{\"description\":\"no name\"}]"));
        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0).arguments()).hasSize(1);
        assertThat(prompts.get(0).arguments().get(0).required()).isTrue();
    }
}
