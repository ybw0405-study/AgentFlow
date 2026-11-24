package com.agentflow.workflow.knowledge;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间检索端点：供 Python 引擎的 retrieval 节点查询文档切片。
 * 仅接受服务间令牌，且按 tenantId 逐 kbId 复核归属；跨租户读不到数据也不泄露存在性。
 */
@RestController
@RequestMapping("/internal/knowledge")
public class InternalKnowledgeController {

    private final KnowledgeService service;
    private final String internalToken;

    public InternalKnowledgeController(KnowledgeService service,
            @Value("${agentflow.gateway.internal-token}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken;
    }

    public record InternalSearchRequest(String tenantId, String spaceId,
                                        List<String> knowledgeBaseIds, String query,
                                        Integer topK, Double minScore) {
    }

    public record InternalChunksRequest(String tenantId, List<String> knowledgeBaseIds,
                                        Integer limit) {
    }

    @PostMapping("/chunks")
    public ApiResponse<List<KnowledgeService.ChunkDto>> chunks(
            HttpServletRequest request, @RequestBody InternalChunksRequest body) {
        requireInternalToken(request);
        if (body.tenantId() == null || body.tenantId().isBlank()) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "tenantId is required");
        }
        return ApiResponse.ok(service.listChunksInternal(body.tenantId(),
                body.knowledgeBaseIds(), body.limit() == null ? 2000 : body.limit()));
    }

    @PostMapping("/search")
    public ApiResponse<KnowledgeService.SearchResult> search(
            HttpServletRequest request, @RequestBody InternalSearchRequest body) {
        requireInternalToken(request);
        String tenantId = body.tenantId() == null ? body.spaceId() : body.tenantId();
        if (tenantId == null || tenantId.isBlank() || body.query() == null) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001,
                    "tenantId and query are required");
        }
        return ApiResponse.ok(service.searchInternal(tenantId, body.knowledgeBaseIds(),
                body.query(), body.topK() == null ? 5 : body.topK(),
                body.minScore() == null ? 0.0 : body.minScore()));
    }

    private void requireInternalToken(HttpServletRequest request) {
        String token = request.getHeader("X-Internal-Token");
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
    }
}
