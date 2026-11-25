package com.agentflow.common.tenant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 三级租户继承（架构 §11 P2）。
 *
 * <p>租户树形如 {@code ORG → TEAM → SPACE}，成员关系可以直接挂在任意一层。
 * 解析顺序：
 * <ol>
 * <li>直接成员：角色原样使用，source=DIRECT；</li>
 * <li>祖先成员：沿 parent_id 由近及远查找，命中即继承，source=INHERITED，
 * 其中 OWNER 降级为 ADMIN —— 组织所有者不应在子空间同样拥有「所有者」
 * 语义（子空间可被删除/移交）；</li>
 * <li>平台 ADMIN：无任何成员关系时兜底 ADMIN，source=PLATFORM。</li>
 * </ol>
 *
 * <p>向上遍历最多 {@value #MAX_DEPTH} 层并做环检测；任何一步查不到节点即视为
 * 「空间不存在」，返回 empty（调用方按 404 处理，不泄露资源存在性）。
 */
public final class TenantHierarchyResolver {

    public static final int MAX_DEPTH = 8;

    private final TenantTreePort port;

    public TenantHierarchyResolver(TenantTreePort port) {
        this.port = port;
    }

    /** 解析结果。role 为对目标空间生效的角色，inheritedFrom 为命中的祖先（直接成员为 null）。 */
    public record Resolved(String tenantId, String userId, String role,
                           String inheritedFrom, Source source) {

        public enum Source { DIRECT, INHERITED, PLATFORM }

        public boolean atLeast(String required) {
            return rank(role) >= rank(required);
        }

        public boolean inherited() {
            return source != Source.DIRECT;
        }
    }

    public Optional<Resolved> resolve(String spaceId, String userId) {
        if (spaceId == null || spaceId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        List<TenantTreePort.TenantNode> chain = chainOf(spaceId);
        if (chain.isEmpty()) {
            return Optional.empty();
        }
        for (int depth = 0; depth < chain.size(); depth++) {
            TenantTreePort.TenantNode node = chain.get(depth);
            String role = port.directRole(node.id(), userId);
            if (role == null) {
                continue;
            }
            if (depth == 0) {
                return Optional.of(new Resolved(spaceId, userId, role, null,
                        Resolved.Source.DIRECT));
            }
            return Optional.of(new Resolved(spaceId, userId, inherit(role), node.id(),
                    Resolved.Source.INHERITED));
        }
        if ("ADMIN".equals(port.platformRole(userId))) {
            return Optional.of(new Resolved(spaceId, userId, "ADMIN", null,
                    Resolved.Source.PLATFORM));
        }
        return Optional.empty();
    }

    /**
     * 目标空间到根的链（含自身）。目标空间本身缺失 → 空列表（404 语义）；
     * 祖先缺失或成环 → 截断到已知部分（不因脏数据把合法空间判成不存在）。
     */
    public List<TenantTreePort.TenantNode> chainOf(String spaceId) {
        List<TenantTreePort.TenantNode> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = spaceId;
        while (cursor != null && !cursor.isBlank() && seen.add(cursor)
                && chain.size() < MAX_DEPTH) {
            TenantTreePort.TenantNode node = port.find(cursor);
            if (node == null) {
                if (chain.isEmpty()) {
                    return List.of();
                }
                break;
            }
            chain.add(node);
            cursor = node.parentId();
        }
        return chain;
    }

    /** 祖先 → 子孙的角色映射：仅 OWNER 降级，其余保持。 */
    static String inherit(String role) {
        return "OWNER".equals(role) ? "ADMIN" : role;
    }

    public static int rank(String role) {
        return switch (role == null ? "" : role) {
            case "OWNER" -> 4;
            case "ADMIN" -> 3;
            case "EDITOR" -> 2;
            case "VIEWER" -> 1;
            default -> 0;
        };
    }
}
