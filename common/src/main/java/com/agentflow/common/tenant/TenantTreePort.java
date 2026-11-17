package com.agentflow.common.tenant;

/**
 * 租户树读取端口（由各服务用 MyBatis 实现）。
 * 只暴露解析所需的三个原子查询，避免把 SQL 细节带进 common。
 */
public interface TenantTreePort {

    /** 未删除的租户节点；不存在返回 null。 */
    TenantNode find(String tenantId);

    /** 该租户上的直接成员角色（不含继承）；非成员返回 null。 */
    String directRole(String tenantId, String userId);

    /** 平台级角色（app_user.platform_role）；无记录返回 null。 */
    String platformRole(String userId);

    record TenantNode(String id, String parentId, String type) {
    }
}
