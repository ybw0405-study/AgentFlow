package com.agentflow.workflow.workflow;

import java.sql.Timestamp;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkflowDefinitionMapper {

    @Insert("INSERT INTO workflow_definition"
            + " (id, tenant_id, name, description, status, draft_dsl, created_by)"
            + " VALUES (#{id}, #{tenantId}, #{name}, #{description}, 'DRAFT', #{draftDsl},"
            + " #{createdBy})")
    int insert(DefinitionRow row);

    @Select("SELECT seq, id, tenant_id AS tenantId, name, description, status,"
            + " current_version_id AS currentVersionId, max_concurrent AS maxConcurrent,"
            + " draft_dsl AS draftDsl,"
            + " draft_checksum AS draftChecksum, created_by AS createdBy,"
            + " created_at AS createdAt, updated_at AS updatedAt"
            + " FROM workflow_definition"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    DefinitionRow findByIdAndTenant(@Param("id") String id, @Param("tenantId") String tenantId);

    @Select("<script>"
            + "SELECT seq, id, tenant_id AS tenantId, name, description, status,"
            + " current_version_id AS currentVersionId, max_concurrent AS maxConcurrent,"
            + " draft_dsl AS draftDsl,"
            + " draft_checksum AS draftChecksum, created_by AS createdBy,"
            + " created_at AS createdAt, updated_at AS updatedAt"
            + " FROM workflow_definition"
            + " WHERE tenant_id = #{tenantId} AND deleted_at IS NULL AND seq &gt; #{afterSeq}"
            + "<if test='status != null'> AND status = #{status}</if>"
            + "<if test='keyword != null'> AND (name LIKE CONCAT('%', #{keyword}, '%')"
            + " OR description LIKE CONCAT('%', #{keyword}, '%'))</if>"
            + " ORDER BY seq LIMIT #{limit}"
            + "</script>")
    List<DefinitionRow> listByTenant(@Param("tenantId") String tenantId,
                                     @Param("status") String status,
                                     @Param("keyword") String keyword,
                                     @Param("afterSeq") long afterSeq,
                                     @Param("limit") int limit);

    @Update("UPDATE workflow_definition SET name = #{name}, description = #{description}"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int updateMeta(@Param("id") String id, @Param("tenantId") String tenantId,
                   @Param("name") String name, @Param("description") String description);

    @Update("UPDATE workflow_definition SET status = #{status}"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int updateStatus(@Param("id") String id, @Param("tenantId") String tenantId,
                     @Param("status") String status);

    /** 并发上限：0 = 不限。改它只影响「下一个执行能不能进来」，不动已在跑的。 */
    @Update("UPDATE workflow_definition SET max_concurrent = #{maxConcurrent}"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int updateMaxConcurrent(@Param("id") String id, @Param("tenantId") String tenantId,
                            @Param("maxConcurrent") int maxConcurrent);

    @Update("UPDATE workflow_definition SET deleted_at = NOW(3)"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int softDelete(@Param("id") String id, @Param("tenantId") String tenantId);

    @Update("UPDATE workflow_definition"
            + " SET draft_dsl = #{draftDsl}, draft_checksum = #{checksum},"
            + "     draft_updated_at = NOW(3)"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int updateDraft(@Param("id") String id, @Param("tenantId") String tenantId,
                    @Param("draftDsl") String draftDsl, @Param("checksum") String checksum);

    @Update("UPDATE workflow_definition SET current_version_id = #{versionId},"
            + " status = 'PUBLISHED'"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int updateCurrentVersion(@Param("id") String id, @Param("tenantId") String tenantId,
                             @Param("versionId") String versionId);

    record DefinitionRow(long seq, String id, String tenantId, String name, String description,
                         String status, String currentVersionId, int maxConcurrent,
                         String draftDsl, String draftChecksum, String createdBy,
                         Timestamp createdAt, Timestamp updatedAt) {
    }
}
