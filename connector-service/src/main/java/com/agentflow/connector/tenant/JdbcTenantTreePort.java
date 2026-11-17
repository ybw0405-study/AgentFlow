package com.agentflow.connector.tenant;

import com.agentflow.common.tenant.TenantTreePort;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 租户树端口实现（JdbcTemplate）：节点 / 直接角色 / 平台角色三次点查。 */
@Component
public class JdbcTenantTreePort implements TenantTreePort {

    private final JdbcTemplate jdbc;

    public JdbcTenantTreePort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TenantNode find(String tenantId) {
        List<TenantNode> rows = jdbc.query(
                "SELECT id, parent_id, type FROM tenant WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> new TenantNode(rs.getString("id"), rs.getString("parent_id"),
                        rs.getString("type")), tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public String directRole(String tenantId, String userId) {
        List<String> roles = jdbc.queryForList(
                "SELECT role FROM tenant_member WHERE tenant_id = ? AND user_id = ?",
                String.class, tenantId, userId);
        return roles.isEmpty() ? null : roles.get(0);
    }

    @Override
    public String platformRole(String userId) {
        List<String> roles = jdbc.queryForList(
                "SELECT platform_role FROM app_user WHERE id = ? AND deleted_at IS NULL",
                String.class, userId);
        return roles.isEmpty() ? null : roles.get(0);
    }
}
