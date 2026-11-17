package com.agentflow.workflow.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentflow.workflow.tenant.MybatisTenantTreePort;
import com.agentflow.workflow.tenant.TenantMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 审批人展开：三级继承、角色阈值、USERS 白名单、空集兜底。 */
class GateApproversTest {

    private final TenantMapper tenants = mock(TenantMapper.class);
    private final GateApprovers approvers =
            new GateApprovers(tenants, new MybatisTenantTreePort(tenants));

    @BeforeEach
    void chain() {
        when(tenants.findNode("space-a"))
                .thenReturn(new TenantMapper.TenantNodeRow("space-a", "org-1", "SPACE"));
        when(tenants.findNode("org-1"))
                .thenReturn(new TenantMapper.TenantNodeRow("org-1", null, "ORG"));
        when(tenants.listMembers("space-a")).thenReturn(List.of(
                new TenantMapper.MemberRow("u-admin", "ADMIN"),
                new TenantMapper.MemberRow("u-editor", "EDITOR")));
        when(tenants.listMembers("org-1")).thenReturn(List.of(
                new TenantMapper.MemberRow("u-org", "OWNER")));
        when(tenants.listPlatformAdmins()).thenReturn(List.of("u-platform"));
    }

    @Test
    void roleRuleIncludesAncestorMembersAndPlatformAdmins() {
        List<String> result = approvers.resolve("space-a", Map.of("type", "ROLE", "role", "ADMIN"));
        assertEquals(List.of("u-admin", "u-org", "u-platform"), result);
    }

    @Test
    void roleRuleEditorIncludesEditorsToo() {
        List<String> result = approvers.resolve("space-a", Map.of("type", "ROLE", "role", "EDITOR"));
        assertTrue(result.contains("u-editor"));
        assertTrue(result.contains("u-admin"));
        // 要求 EDITOR 时不需要拉平台管理员兜底
        assertEquals(List.of("u-admin", "u-editor", "u-org"), result);
    }

    @Test
    void usersRuleKeepsOnlyVisibleMembers() {
        when(tenants.findMemberRole("space-a", "u-editor")).thenReturn("EDITOR");
        when(tenants.findMemberRole("space-a", "u-stranger")).thenReturn(null);
        when(tenants.findMemberRole("org-1", "u-stranger")).thenReturn(null);
        when(tenants.findMemberRole("space-a", "u-org")).thenReturn(null);
        when(tenants.findMemberRole("org-1", "u-org")).thenReturn("OWNER");

        List<String> result = approvers.resolve("space-a", Map.of(
                "type", "USERS", "userIds", List.of("u-editor", "u-stranger", "u-org")));
        assertEquals(List.of("u-editor", "u-org"), result);
    }

    @Test
    void emptyResolutionFailsClosed() {
        when(tenants.findMemberRole("space-a", "u-stranger")).thenReturn(null);
        when(tenants.findMemberRole("org-1", "u-stranger")).thenReturn(null);

        // USERS 规则里的人都不在可见范围 → 空集不再 fail-open 到全体平台管理员
        List<String> result = approvers.resolve("space-a", Map.of(
                "type", "USERS", "userIds", List.of("u-stranger")));
        assertTrue(result.isEmpty());
    }

    @Test
    void roleAdminStillIncludesPlatformAdminsExplicitly() {
        // ROLE+ADMIN 是设计上显式的运维兜底通道，不属于 fail-open，须保留
        when(tenants.listMembers("space-a")).thenReturn(List.of(
                new TenantMapper.MemberRow("u-viewer", "VIEWER")));
        when(tenants.listMembers("org-1")).thenReturn(List.of());

        List<String> result = approvers.resolve("space-a", Map.of("type", "ROLE", "role", "ADMIN"));
        assertEquals(List.of("u-platform"), result);
    }
}
