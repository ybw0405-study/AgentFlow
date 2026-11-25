package com.agentflow.workflow.gate;

import com.agentflow.common.tenant.TenantHierarchyResolver;
import com.agentflow.common.tenant.TenantTreePort;
import com.agentflow.workflow.tenant.MybatisTenantTreePort;
import com.agentflow.workflow.tenant.TenantMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审批人展开：把 DSL 里的 approvers 规则解析成具体用户 id（挂起瞬间冻结）。
 *
 * <p>规则语义：
 * <ul>
 * <li>ROLE：执行空间及其祖先上「角色 ≥ 要求」的成员（三级继承）；要求 ≥ ADMIN 时
 * 额外并入平台管理员（运维兜底通道）。</li>
 * <li>USERS：显式名单，但只保留在该空间可见范围内有成员关系的人，
 * 避免把审批权投递给无关租户的用户。</li>
 * <li>EXPRESSION：对「空间链上的成员 + 平台管理员」逐个求值，命中者进审批人快照
 * （文法见 {@link GateExpression}）。不先按角色粗筛是有意为之——表达式本身
 * 就在描述角色之外的条件（点名、排除、组合条件）。</li>
 * </ul>
 *
 * <p>结果为空时的处置：fail-closed —— 不再静默把审批权扩大到全体平台管理员
 * （这是权限放大风险）。返回空集，仅平台 ADMIN 可经 {@code GateService} 中显式、
 * 可审计的兜底通道裁决；同时以 error 日志暴露规则配置错误，供运维介入。
 */
@Component
public class GateApprovers {

    private static final Logger log = LoggerFactory.getLogger(GateApprovers.class);

    private final TenantMapper tenants;
    private final TenantHierarchyResolver resolver;

    public GateApprovers(TenantMapper tenants, MybatisTenantTreePort port) {
        this.tenants = tenants;
        this.resolver = new TenantHierarchyResolver(port);
    }

    public List<String> resolve(String spaceId, Map<String, Object> rule) {
        String type = String.valueOf(rule.getOrDefault("type", "ROLE"));
        List<String> chain = resolver.chainOf(spaceId).stream()
                .map(TenantTreePort.TenantNode::id)
                .toList();
        if (chain.isEmpty()) {
            chain = List.of(spaceId);
        }
        Set<String> approvers = new LinkedHashSet<>();

        if ("USERS".equals(type)) {
            Object raw = rule.get("userIds");
            if (raw instanceof List<?> ids) {
                for (Object id : ids) {
                    String userId = String.valueOf(id);
                    if (isMemberOfAny(chain, userId)) {
                        approvers.add(userId);
                    }
                }
            }
        } else if ("EXPRESSION".equals(type)) {
            resolveByExpression(spaceId, chain, rule, approvers);
        } else {
            String required = String.valueOf(rule.getOrDefault("role", "ADMIN"));
            int requiredRank = TenantHierarchyResolver.rank(required);
            for (String tenantId : chain) {
                for (TenantMapper.MemberRow member : tenants.listMembers(tenantId)) {
                    if (TenantHierarchyResolver.rank(member.role()) >= requiredRank) {
                        approvers.add(member.userId());
                    }
                }
            }
            if (requiredRank >= TenantHierarchyResolver.rank("ADMIN")) {
                approvers.addAll(tenants.listPlatformAdmins());
            }
        }

        if (approvers.isEmpty()) {
            log.error("gate approvers resolved empty for space {} rule {}; fail-closed: "
                    + "no automatic platform-admin fallback, only explicit ADMIN decision path",
                    spaceId, rule);
        }
        return List.copyOf(approvers);
    }

    private void resolveByExpression(String spaceId, List<String> chain,
                                     Map<String, Object> rule, Set<String> approvers) {
        String expression = rule.get("expression") == null
                ? "" : String.valueOf(rule.get("expression")).trim();
        if (expression.isEmpty()) {
            log.error("gate approver EXPRESSION without expression, space={} rule={}",
                    spaceId, rule);
            return;
        }
        try {
            GateExpression.validate(expression);
        } catch (IllegalArgumentException e) {
            log.error("gate approver expression invalid, space={} expr={} ({})",
                    spaceId, expression, e.getMessage());
            return;
        }

        Set<String> platformAdmins = new LinkedHashSet<>(tenants.listPlatformAdmins());
        for (String tenantId : chain) {
            for (TenantMapper.MemberRow member : tenants.listMembers(tenantId)) {
                if (matches(expression, spaceId, member.userId(), member.role(),
                        platformAdmins.contains(member.userId()))) {
                    approvers.add(member.userId());
                }
            }
        }
        // 平台管理员未必在空间成员表里：单独过一遍，保证「值班人」类规则不会静默落空
        for (String adminId : platformAdmins) {
            if (matches(expression, spaceId, adminId, "ADMIN", true)) {
                approvers.add(adminId);
            }
        }
    }

    private boolean matches(String expression, String spaceId, String userId, String role,
                            boolean platformAdmin) {
        return GateExpression.evaluate(expression, userId, role,
                TenantHierarchyResolver.rank(role), platformAdmin, spaceId);
    }

    private boolean isMemberOfAny(List<String> tenantIds, String userId) {
        for (String tenantId : tenantIds) {
            if (resolver.resolve(tenantId, userId).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
