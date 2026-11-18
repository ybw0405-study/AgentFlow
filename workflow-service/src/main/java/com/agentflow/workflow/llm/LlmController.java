package com.agentflow.workflow.llm;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.workflow.engine.EngineGatewayClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 画布可用的模型清单。只读、无租户数据，登录即可访问；
 * 端点与密钥仍只存在于引擎进程，这里拿到的只有模型 id。
 */
@RestController
@RequestMapping("/api/v1/llm")
public class LlmController {

    private final EngineGatewayClient engine;

    public LlmController(EngineGatewayClient engine) {
        this.engine = engine;
    }

    @GetMapping("/models")
    public ApiResponse<EngineGatewayClient.LlmModelsResponse> models() {
        return ApiResponse.ok(engine.listLlmModels());
    }
}
