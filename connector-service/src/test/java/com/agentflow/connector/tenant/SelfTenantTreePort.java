package com.agentflow.connector.tenant;

import com.agentflow.common.tenant.TenantTreePort;

/**
 * 单测用租户树：任意 id 解析为「无父的空间」，因此 chainOf(x) == [x]。
 * 让连接器调用测试专注出网/凭据/幂等行为，不必搭一棵真实三级树。
 */
public final class SelfTenantTreePort implements TenantTreePort {

    @Override
    public TenantNode find(String tenantId) {
        return new TenantNode(tenantId, null, "SPACE");
    }

    @Override
    public String directRole(String tenantId, String userId) {
        return null;
    }

    @Override
    public String platformRole(String userId) {
        return null;
    }
}
