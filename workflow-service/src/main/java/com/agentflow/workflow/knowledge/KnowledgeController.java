package com.agentflow.workflow.knowledge;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 文档知识库的用户侧接口：建库、导入文档、列表、删除、检索自测。 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    public record CreateBaseRequest(@NotBlank @Size(max = 128) String name,
                                    @Size(max = 512) String description) {
    }

    public record AddDocumentRequest(@Size(max = 256) String title,
                                     @NotBlank String content,
                                     @Size(max = 32) String source) {
    }

    public record SearchRequest(List<String> knowledgeBaseIds,
                                @NotBlank String query,
                                Integer topK, Double minScore) {
    }

    @GetMapping("/bases")
    public ApiResponse<List<KnowledgeService.BaseDto>> bases() {
        return ApiResponse.ok(service.listBases(TenantContext.require()));
    }

    @PostMapping("/bases")
    public ApiResponse<KnowledgeService.BaseDto> createBase(
            @Validated @RequestBody CreateBaseRequest body) {
        return ApiResponse.ok(service.createBase(TenantContext.require(), body.name(),
                body.description()));
    }

    @DeleteMapping("/bases/{id}")
    public ApiResponse<Map<String, Object>> deleteBase(@PathVariable String id) {
        service.deleteBase(TenantContext.require(), id);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @GetMapping("/bases/{id}/documents")
    public ApiResponse<List<KnowledgeService.DocumentDto>> documents(@PathVariable String id) {
        return ApiResponse.ok(service.listDocuments(TenantContext.require(), id));
    }

    @PostMapping("/bases/{id}/documents")
    public ApiResponse<KnowledgeService.DocumentDto> addDocument(
            @PathVariable String id, @Validated @RequestBody AddDocumentRequest body) {
        return ApiResponse.ok(service.addDocument(TenantContext.require(), id, body.title(),
                body.content(), body.source()));
    }

    /** 文件导入：PDF / Word(docx) / PPT(pptx) / Excel(xlsx) / 纯文本，解析正文后切片入库。 */
    @PostMapping("/bases/{id}/documents/upload")
    public ApiResponse<KnowledgeService.DocumentDto> uploadDocument(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "上传文件为空");
        }
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "文件读取失败");
        }
        return ApiResponse.ok(service.addDocumentFromFile(TenantContext.require(), id, title,
                file.getOriginalFilename(), bytes));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResponse<Map<String, Object>> deleteDocument(@PathVariable String id) {
        service.deleteDocument(TenantContext.require(), id);
        return ApiResponse.ok(Map.of("ok", true));
    }

    /** 画布配置与知识库页共用：选多个知识库试一次检索，确认文档确实能被召回。 */
    @PostMapping("/search")
    public ApiResponse<KnowledgeService.SearchResult> search(
            @Validated @RequestBody SearchRequest body) {
        return ApiResponse.ok(service.search(TenantContext.require(), body.knowledgeBaseIds(),
                body.query(), body.topK() == null ? 5 : body.topK(),
                body.minScore() == null ? 0.0 : body.minScore()));
    }
}
