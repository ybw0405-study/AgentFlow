package com.agentflow.workflow.workflow;

import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.FieldViolation;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.audit.AuditMessage;
import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.workflow.engine.EngineGatewayClient.DslValidationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工作流定义/草稿/版本/发布。版本不可变由触发器 + 应用层双重保证（US-01）。 */
@Service
public class WorkflowService {

    /** 新建定义的默认草稿：最小合法 start→end 骨架。 */
    private static final String DEFAULT_DRAFT = """
            {
              "dslVersion": "1.0",
              "variables": {"question": {"type": "string", "required": true, "pii": true}},
              "nodes": [
                {"key": "start", "type": "start", "config": {},
                 "position": {"x": 80, "y": 160}},
                {"key": "end", "type": "end",
                 "config": {"resultMapping": {"answer": "{{ vars.question }}"}},
                 "position": {"x": 480, "y": 160}}
              ],
              "edges": [{"from": "start", "to": "end"}]
            }
            """;

    private final WorkflowDefinitionMapper definitions;
    private final WorkflowVersionMapper versions;
    private final EngineGatewayClient engine;
    private final AuditPublisher audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkflowService(WorkflowDefinitionMapper definitions,
                           WorkflowVersionMapper versions,
                           EngineGatewayClient engine, AuditPublisher audit) {
        this.definitions = definitions;
        this.versions = versions;
        this.engine = engine;
        this.audit = audit;
    }

    public record CreateResult(String id, String name, String status) {
    }

    public CreateResult create(TenantInfo tenant, String name, String description) {
        requireRole(tenant, "EDITOR", null);
        String id = UUID.randomUUID().toString();
        try {
            definitions.insert(new WorkflowDefinitionMapper.DefinitionRow(
                    0L, id, tenant.tenantId(), name, description, "DRAFT", null,
                    DEFAULT_DRAFT, null, tenant.userId(), null, null));
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.COM_CONFLICT_001,
                    "workflow name already exists in this space");
        }
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "WORKFLOW_CREATED", Map.of("definitionId", id, "name", name)));
        return new CreateResult(id, name, "DRAFT");
    }

    public PageResult<WorkflowDefinitionMapper.DefinitionRow> list(
            TenantInfo tenant, String status, String keyword, String cursor, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), 100);
        var rows = definitions.listByTenant(tenant.tenantId(), status, keyword,
                PageResult.decode(cursor), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        var items = hasMore ? rows.subList(0, pageSize) : rows;
        Long lastSeq = items.isEmpty() ? null : items.get(items.size() - 1).seq();
        return PageResult.of(items, lastSeq, hasMore);
    }

    public WorkflowDefinitionMapper.DefinitionRow detail(TenantInfo tenant, String id) {
        return load(tenant, id);
    }

    public void updateMeta(TenantInfo tenant, String id, String name, String description) {
        requireRole(tenant, "EDITOR", null);
        load(tenant, id);
        definitions.updateMeta(id, tenant.tenantId(), name, description);
    }

    public void archive(TenantInfo tenant, String id) {
        requireRole(tenant, "EDITOR", null);
        load(tenant, id);
        definitions.updateStatus(id, tenant.tenantId(), "ARCHIVED");
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "WORKFLOW_ARCHIVED", Map.of("definitionId", id)));
    }

    public void delete(TenantInfo tenant, String id) {
        requireRole(tenant, "ADMIN", null);
        load(tenant, id);
        definitions.softDelete(id, tenant.tenantId());
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "WORKFLOW_DELETED", Map.of("definitionId", id)));
    }

    /** 保存草稿：引擎校验通过才落库；失败 422 带逐节点错误。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveDraft(TenantInfo tenant, String id, Map<String, Object> dsl) {
        requireRole(tenant, "EDITOR", null);
        load(tenant, id);
        DslValidationResponse result = engine.validateDsl(dsl);
        if (result == null) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "engine validate failed");
        }
        if (!result.ok()) {
            throw dslErrors(result);
        }
        String normalized = writeJson(result.normalizedDsl());
        definitions.updateDraft(id, tenant.tenantId(), normalized, result.checksum());
        return Map.of("checksum", result.checksum(), "ok", true);
    }

    /** 冻结草稿为不可变版本。 */
    @Transactional
    public WorkflowVersionMapper.VersionRow freeze(TenantInfo tenant, String id) {
        requireRole(tenant, "EDITOR", null);
        var def = load(tenant, id);
        if (def.draftDsl() == null) {
            throw new BusinessException(ErrorCode.WF_STATE_001, "no draft to freeze");
        }
        Map<String, Object> draft = readJson(def.draftDsl());
        DslValidationResponse result = engine.validateDsl(draft);
        if (result == null || !result.ok()) {
            throw dslErrors(result);
        }
        int versionNo = versions.maxVersionNo(id) + 1;
        var row = new WorkflowVersionMapper.VersionRow(
                UUID.randomUUID().toString(), id, versionNo,
                writeJson(result.normalizedDsl()), result.checksum(),
                null, null, tenant.userId(), null);
        versions.insert(row);
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "WORKFLOW_VERSION_FROZEN",
                Map.of("definitionId", id, "versionNo", versionNo)));
        return versions.findById(row.id());
    }

    @Transactional
    public void publish(TenantInfo tenant, String id, String versionId) {
        requireRole(tenant, "ADMIN", null);
        var def = load(tenant, id);
        var version = versions.findById(versionId);
        if (version == null || !version.definitionId().equals(id)) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
        versions.markPublished(versionId, tenant.userId());
        definitions.updateCurrentVersion(id, tenant.tenantId(), versionId);
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "WORKFLOW_PUBLISHED",
                Map.of("definitionId", id, "versionId", versionId,
                        "versionNo", version.versionNo())));
    }

    /**
     * 回滚 = 只把 current 指针移回历史版本（US-01）；历史版本本身不动。
     *
     * <p>不能委派 {@link #publish}：publish 会 markPublished 覆盖原发布人/时间，
     * 且审计类型恒为 WORKFLOW_PUBLISHED，导致发布历史被回滚者篡改、审计失真。
     */
    @Transactional
    public void rollback(TenantInfo tenant, String id, String versionId) {
        requireRole(tenant, "ADMIN", null);
        load(tenant, id);  // 租户隔离校验：跨租户 404
        var version = versions.findById(versionId);
        if (version == null || !version.definitionId().equals(id)) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
        // 仅移动 current 指针；不触碰目标版本的 published_at/published_by
        definitions.updateCurrentVersion(id, tenant.tenantId(), versionId);
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "WORKFLOW_ROLLED_BACK",
                Map.of("definitionId", id, "versionId", versionId,
                        "versionNo", version.versionNo())));
    }

    public List<WorkflowVersionMapper.VersionRow> listVersions(TenantInfo tenant, String id) {
        load(tenant, id);
        return versions.listByDefinition(id);
    }

    WorkflowDefinitionMapper.DefinitionRow load(TenantInfo tenant, String id) {
        var def = definitions.findByIdAndTenant(id, tenant.tenantId());
        if (def == null) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
        return def;
    }

    void requireRole(TenantInfo tenant, String role, String executionId) {
        if (!tenant.atLeast(role)) {
            audit.publish(AuditMessage.of(tenant.tenantId(), executionId, tenant.userId(),
                    "WF_FORBIDDEN_ATTEMPT", Map.of("requiredRole", role)));
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
    }

    private BusinessException dslErrors(DslValidationResponse result) {
        List<FieldViolation> errors = result == null || result.errors() == null
                ? List.of()
                : result.errors().stream()
                        .map(e -> new FieldViolation(
                                e.nodeKey() != null ? e.nodeKey()
                                        : (e.path() != null ? e.path() : "-"),
                                e.code() + ": " + e.message()))
                        .toList();
        String code = errors.isEmpty() ? "DSL-VALIDATION-001"
                : firstCode(result.errors().get(0).code());
        ErrorCode errorCode = ErrorCode.find(code).orElse(ErrorCode.DSL_VALIDATION_001);
        return new BusinessException(errorCode, "DSL validation failed", errors);
    }

    private static String firstCode(String code) {
        return code == null ? "DSL-VALIDATION-001" : code;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
