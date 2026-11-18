package com.agentflow.workflow.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.workflow.engine.EngineGatewayClient.LlmModelsResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 模型清单只做透传：租户过滤、鉴权都在既有的 filter/controller 层完成。 */
class LlmControllerTest {

    @Test
    void returnsEngineCatalogAsIs() {
        EngineGatewayClient engine = mock(EngineGatewayClient.class);
        when(engine.listLlmModels()).thenReturn(
                new LlmModelsResponse(true, "deepseek-chat",
                        List.of("deepseek-chat", "deepseek-reasoner"), null));

        ApiResponse<LlmModelsResponse> resp = new LlmController(engine).models();

        assertEquals("OK", resp.code());
        assertEquals("deepseek-chat", resp.data().defaultModel());
        assertEquals(List.of("deepseek-chat", "deepseek-reasoner"), resp.data().models());
        verify(engine).listLlmModels();
    }

    @Test
    void passesThroughUnconfiguredStateWithoutError() {
        EngineGatewayClient engine = mock(EngineGatewayClient.class);
        when(engine.listLlmModels()).thenReturn(new LlmModelsResponse(false, "", List.of(), null));

        ApiResponse<LlmModelsResponse> resp = new LlmController(engine).models();

        assertTrue(resp.data().models().isEmpty());
        assertEquals("", resp.data().defaultModel());
    }
}
