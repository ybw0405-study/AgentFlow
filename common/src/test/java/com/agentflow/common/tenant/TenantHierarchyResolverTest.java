package com.agentflow.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 三级租户继承解析：直接成员 / 祖先继承 / 平台兜底 / 404 语义。 */
class TenantHierarchyResolverTest {

    /** ORG → TEAM → SPACE 三级；beta 直接挂在 ORG 下，用于验证跳级继承。 */
    private static final class FakePort implements TenantTreePort {
        final Map<String, TenantNode> nodes = new HashMap<>();
        final Map<String, Map<String, String>> members = new HashMap<>();
        final Map<String, String> platform = new HashMap<>();

        FakePort() {
            nodes.put("org", new TenantNode("org", null, "ORG"));
            nodes.put("team", new TenantNode("team", "org", "TEAM"));
            nodes.put("space-a", new TenantNode("space-a", "team", "SPACE"));
            nodes.put("space-beta", new TenantNode("space-beta", "org", "SPACE"));
        }

        void member(String tenantId, String userId, String role) {
            members.computeIfAbsent(tenantId, k -> new HashMap<>()).put(userId, role);
        }

        @Override
        public TenantNode find(String tenantId) {
            return nodes.get(tenantId);
        }

        @Override
        public String directRole(String tenantId, String userId) {
            return members.getOrDefault(tenantId, Map.of()).get(userId);
        }

        @Override
        public String platformRole(String userId) {
            return platform.get(userId);
        }
    }

    private final FakePort port = new FakePort();
    private final TenantHierarchyResolver resolver = new TenantHierarchyResolver(port);

    @Test
    void directMembershipKeepsRole() {
        port.member("space-a", "u1", "OWNER");
        var r = resolver.resolve("space-a", "u1").orElseThrow();
        assertThat(r.role()).isEqualTo("OWNER");
        assertThat(r.source()).isEqualTo(TenantHierarchyResolver.Resolved.Source.DIRECT);
        assertThat(r.inheritedFrom()).isNull();
        assertThat(r.inherited()).isFalse();
    }

    @Test
    void orgOwnerInheritsAsAdminAcrossTeamLayer() {
        port.member("org", "u2", "OWNER");
        var r = resolver.resolve("space-a", "u2").orElseThrow();
        assertThat(r.role()).isEqualTo("ADMIN"); // OWNER 降级，避免子空间被组织所有者删除
        assertThat(r.source()).isEqualTo(TenantHierarchyResolver.Resolved.Source.INHERITED);
        assertThat(r.inheritedFrom()).isEqualTo("org");
        assertThat(r.atLeast("ADMIN")).isTrue();
    }

    @Test
    void nearestAncestorWins() {
        port.member("team", "u3", "EDITOR");
        port.member("org", "u3", "VIEWER");
        var r = resolver.resolve("space-a", "u3").orElseThrow();
        assertThat(r.role()).isEqualTo("EDITOR");
        assertThat(r.inheritedFrom()).isEqualTo("team");
    }

    @Test
    void skipsMissingMiddleLayer() {
        port.member("org", "u4", "EDITOR");
        var r = resolver.resolve("space-beta", "u4").orElseThrow();
        assertThat(r.role()).isEqualTo("EDITOR");
        assertThat(r.inheritedFrom()).isEqualTo("org");
    }

    @Test
    void platformAdminFallsBackToAdmin() {
        port.platform.put("root", "ADMIN");
        var r = resolver.resolve("space-beta", "root").orElseThrow();
        assertThat(r.role()).isEqualTo("ADMIN");
        assertThat(r.source()).isEqualTo(TenantHierarchyResolver.Resolved.Source.PLATFORM);
    }

    @Test
    void platformUserWithoutMembershipIsRejected() {
        port.platform.put("someone", "USER");
        assertThat(resolver.resolve("space-a", "someone")).isEmpty();
    }

    @Test
    void unknownSpaceIsEmpty() {
        port.member("org", "u5", "OWNER");
        assertThat(resolver.resolve("space-missing", "u5")).isEmpty();
    }

    @Test
    void cycleInTreeDoesNotLoopForever() {
        port.nodes.put("loop-a", new TenantTreePort.TenantNode("loop-a", "loop-b", "TEAM"));
        port.nodes.put("loop-b", new TenantTreePort.TenantNode("loop-b", "loop-a", "ORG"));
        port.member("loop-b", "u6", "EDITOR");
        var r = resolver.resolve("loop-a", "u6").orElseThrow();
        assertThat(r.role()).isEqualTo("EDITOR");
    }
}
