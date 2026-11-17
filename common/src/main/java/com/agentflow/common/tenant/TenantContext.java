package com.agentflow.common.tenant;

/**
 * 租户上下文（请求级）。tenantId = 目标 Space 级租户；角色按 OWNER>ADMIN>EDITOR>VIEWER 排序。
 *
 * <p>source 为角色来源：DIRECT（本空间直接成员）/ INHERITED（上级租户继承）/
 * PLATFORM（平台管理员兜底）；inheritedFrom 记录命中的祖先租户。
 */
public final class TenantContext {

    public record TenantInfo(String tenantId, String userId, String role,
                             String inheritedFrom, String source) {

        public TenantInfo(String tenantId, String userId, String role) {
            this(tenantId, userId, role, null, "DIRECT");
        }

        public static TenantInfo of(TenantHierarchyResolver.Resolved resolved) {
            return new TenantInfo(resolved.tenantId(), resolved.userId(), resolved.role(),
                    resolved.inheritedFrom(), resolved.source().name());
        }

        public boolean atLeast(String required) {
            return TenantHierarchyResolver.rank(role)
                    >= TenantHierarchyResolver.rank(required);
        }

        public boolean inherited() {
            return !"DIRECT".equals(source);
        }
    }

    private static final ThreadLocal<TenantInfo> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantInfo info) {
        HOLDER.set(info);
    }

    public static TenantInfo get() {
        return HOLDER.get();
    }

    public static TenantInfo require() {
        TenantInfo info = HOLDER.get();
        if (info == null) {
            throw new IllegalStateException("tenant context missing");
        }
        return info;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
