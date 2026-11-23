package com.agentflow.connector.web;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.connector.service.ConnectorInvoker;
import com.agentflow.connector.service.ConnectorInvoker.InvokeCommand;
import com.agentflow.connector.service.ConnectorInvoker.InvokeResult;
import com.agentflow.connector.service.ConnectorService;
import com.agentflow.connector.dto.ConnectorDtos.IdSummary;
import com.agentflow.connector.dto.ConnectorDtos.InvokeRequest;
import com.agentflow.connector.dto.ConnectorDtos.InvokeResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部契约（引擎 → 网关 → 本服务）：连接器目录与调用。
 * 全部要求 X-Internal-Token（InternalTokenFilter）；租户归属按三级继承链校验。
 */
@RestController
@RequestMapping("/internal/connectors")
public class InternalConnectorController {

    private final ConnectorService connectors;
    private final ConnectorInvoker invoker;

    public InternalConnectorController(ConnectorService connectors, ConnectorInvoker invoker) {
        this.connectors = connectors;
        this.invoker = invoker;
    }

    /** 引擎触发前拉取该空间可见且启用的连接器清单（含祖先共享）。 */
    @GetMapping
    public ApiResponse<List<IdSummary>> list(@RequestParam String spaceId) {
        return ApiResponse.ok(connectors.visibleEnabled(spaceId));
    }

    @PostMapping("/{id}/invoke")
    public InvokeResponse invoke(@PathVariable String id, @RequestBody InvokeRequest body) {
        int timeoutMs = body.timeoutMs() == null ? 30_000 : body.timeoutMs();
        IdSummary row = connectors.visibleEnabled(body.tenantId()).stream()
                .filter(r -> r.id().equals(id)).findFirst().orElse(null);
        if (row == null) {
            return new InvokeResponse(false, null, "CONN-NOT_FOUND-001", false, 0);
        }
        InvokeResult result = invoker.invoke(new InvokeCommand(body.tenantId(), id,
                body.callId(), body.operation(), body.params(), timeoutMs, body.traceId(),
                body.mcpMethod()));
        return new InvokeResponse(result.ok(), result.data(), result.errorCode(),
                result.transientError(), result.latencyMs());
    }
}
