package com.agentflow.workflow.tenant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 租户成员与租户查询。 */
@Mapper
public interface TenantMapper {

    @Select("SELECT role FROM tenant_member WHERE tenant_id = #{tenantId} AND user_id = #{userId}")
    String findMemberRole(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Select("SELECT t.id AS id, t.name AS name, t.type AS type, m.role AS role "
            + "FROM tenant_member m JOIN tenant t ON t.id = m.tenant_id "
            + "WHERE m.user_id = #{userId} AND t.deleted_at IS NULL ORDER BY t.created_at")
    List<MembershipRow> listMemberships(@Param("userId") String userId);

    @Select("SELECT id FROM tenant WHERE id = #{tenantId} AND deleted_at IS NULL")
    String findActiveTenantId(@Param("tenantId") String tenantId);

    @Select("SELECT id AS id, parent_id AS parentId, type AS type FROM tenant "
            + "WHERE id = #{tenantId} AND deleted_at IS NULL")
    TenantNodeRow findNode(@Param("tenantId") String tenantId);

    @Select("SELECT platform_role FROM app_user WHERE id = #{userId} AND deleted_at IS NULL")
    String findPlatformRole(@Param("userId") String userId);

    /** 某租户上的直接成员（审批人按 ROLE 展开用）。 */
    @Select("SELECT user_id AS userId, role AS role FROM tenant_member WHERE tenant_id = #{tenantId}")
    List<MemberRow> listMembers(@Param("tenantId") String tenantId);

    @Select("SELECT id FROM app_user WHERE platform_role = 'ADMIN' AND deleted_at IS NULL")
    List<String> listPlatformAdmins();

    @Select("SELECT id AS id, name AS name, parent_id AS parentId, type AS type"
            + " FROM tenant WHERE deleted_at IS NULL ORDER BY created_at")
    List<TenantRow> listTenants();

    /** 某租户子树下的全部空间（含自身）：三级继承的「可进入空间」来源。 */
    @Select("WITH RECURSIVE subtree AS ("
            + " SELECT id, name, type FROM tenant WHERE id = #{tenantId} AND deleted_at IS NULL"
            + " UNION ALL"
            + " SELECT t.id, t.name, t.type FROM tenant t"
            + " JOIN subtree s ON t.parent_id = s.id WHERE t.deleted_at IS NULL)"
            + " SELECT id AS id, name AS name, type AS type, NULL AS role"
            + " FROM subtree WHERE type = 'SPACE' ORDER BY name")
    List<MembershipRow> listDescendantSpaces(@Param("tenantId") String tenantId);

    @Select("SELECT id AS id, name AS name, type AS type, NULL AS role FROM tenant"
            + " WHERE type = 'SPACE' AND deleted_at IS NULL ORDER BY name")
    List<MembershipRow> listSpaces();

    record MembershipRow(String id, String name, String type, String role) {
    }

    record MemberRow(String userId, String role) {
    }

    record TenantRow(String id, String name, String parentId, String type) {
    }

    record TenantNodeRow(String id, String parentId, String type) {
    }
}
