package com.agentflow.workflow.tenant;

import com.agentflow.common.tenant.TenantTreePort;
import org.springframework.stereotype.Component;

/** TenantTreePort 的 MyBatis 实现：三次点查，不做递归 SQL（层级 ≤ 8，链路短）。 */
@Component
public class MybatisTenantTreePort implements TenantTreePort {

    private final TenantMapper mapper;

    public MybatisTenantTreePort(TenantMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TenantNode find(String tenantId) {
        var row = mapper.findNode(tenantId);
        return row == null ? null : new TenantNode(row.id(), row.parentId(), row.type());
    }

    @Override
    public String directRole(String tenantId, String userId) {
        return mapper.findMemberRole(tenantId, userId);
    }

    @Override
    public String platformRole(String userId) {
        return mapper.findPlatformRole(userId);
    }
}
