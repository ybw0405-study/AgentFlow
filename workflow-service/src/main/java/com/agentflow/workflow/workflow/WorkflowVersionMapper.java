package com.agentflow.workflow.workflow;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkflowVersionMapper {

    @Insert("INSERT INTO workflow_version"
            + " (id, definition_id, version_no, dsl_json, dsl_checksum, immutable, created_by)"
            + " VALUES (#{id}, #{definitionId}, #{versionNo}, #{dslJson}, #{dslChecksum}, 1,"
            + " #{createdBy})")
    int insert(VersionRow row);

    @Select("SELECT id, definition_id AS definitionId, version_no AS versionNo,"
            + " dsl_json AS dslJson, dsl_checksum AS dslChecksum, published_at AS publishedAt,"
            + " published_by AS publishedBy, created_by AS createdBy, created_at AS createdAt"
            + " FROM workflow_version WHERE id = #{id}")
    VersionRow findById(@Param("id") String id);

    @Select("SELECT id, definition_id AS definitionId, version_no AS versionNo,"
            + " dsl_json AS dslJson, dsl_checksum AS dslChecksum, published_at AS publishedAt,"
            + " published_by AS publishedBy, created_by AS createdBy, created_at AS createdAt"
            + " FROM workflow_version WHERE definition_id = #{definitionId}"
            + " ORDER BY version_no DESC")
    List<VersionRow> listByDefinition(@Param("definitionId") String definitionId);

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM workflow_version"
            + " WHERE definition_id = #{definitionId}")
    int maxVersionNo(@Param("definitionId") String definitionId);

    /**
     * 仅更新发布元数据，且只写一次：dsl/checksum 与 immutable 由触发器与不可变约定双重保护
     * （US-01）。COALESCE 保证重复发布同一版本不会重写 published_at/published_by，
     * 既保留首次发布历史，也不会撞 V019 的不可变触发器。
     */
    @Update("UPDATE workflow_version SET published_at = COALESCE(published_at, NOW(3)),"
            + " published_by = COALESCE(published_by, #{publishedBy}) WHERE id = #{id}")
    int markPublished(@Param("id") String id, @Param("publishedBy") String publishedBy);

    record VersionRow(String id, String definitionId, int versionNo, String dslJson,
                      String dslChecksum, Timestamp publishedAt, String publishedBy,
                      String createdBy, Timestamp createdAt) {
    }
}
