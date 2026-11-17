package com.agentflow.workflow.internal;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import com.agentflow.workflow.workflow.WorkflowVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部端点：供 Python 引擎执行 subworkflow 节点时查询目标工作流的已发布 DSL 快照。
 * 仅接受服务间令牌 + 空间隔离校验；不经 JWT/TenantFilter（路径不在 /api/v1/ 下）。
 * 跨租户一律 404，不泄露资源存在性（§5.3）。
 */
@RestController
@RequestMapping("/internal")
public class InternalDslController {

    private final WorkflowDefinitionMapper definitions;
    private final WorkflowVersionMapper versions;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public InternalDslController(WorkflowDefinitionMapper definitions,
                                 WorkflowVersionMapper versions,
                                 ObjectMapper objectMapper,
                                 @Value("${agentflow.gateway.internal-token}") String internalToken) {
        this.definitions = definitions;
        this.versions = versions;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    @GetMapping("/workflows/{id}/dsl")
    public ApiResponse<Map<String, Object>> dsl(
            HttpServletRequest request,
            @PathVariable String id,
            @RequestParam(required = false) String versionId,
            @RequestParam String spaceId) {
        requireInternalToken(request);
        var def = definitions.findByIdAndTenant(id, spaceId);
        if (def == null) {
            throw notFound();
        }
        String targetVersion = versionId != null ? versionId : def.currentVersionId();
        if (targetVersion == null) {
            throw notFound();
        }
        var version = versions.findById(targetVersion);
        if (version == null || !id.equals(version.definitionId())) {
            throw notFound();
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(
                    version.dslJson(), new TypeReference<>() {});
            return ApiResponse.ok(Map.of(
                    "definitionId", id,
                    "versionId", version.id(),
                    "versionNo", version.versionNo(),
                    "dsl", snapshot));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "invalid stored DSL snapshot");
        }
    }

    private void requireInternalToken(HttpServletRequest request) {
        String token = request.getHeader("X-Internal-Token");
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw notFound();
        }
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
    }
}
