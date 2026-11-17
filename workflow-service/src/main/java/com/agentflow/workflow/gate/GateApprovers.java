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
 *   <li>ROLE：执行空间及其祖先上「角色 ≥ 要求」的成员（三级继承）；要求 ≥ ADMIN 时
 *       额外并入平台管理员（运维兜底通道）。</li>
 *   <li>USERS：显式名单，但只保留在该空间可见范围内有成员关系的人，
 *       避免把审批权投递给无关租户的用户。</li>
 *   <li>EXPRESSION：P3 不做用户属性表达式求值（引擎侧编译期已拒绝）。</li>
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

    @SuppressWarnings("unchecked")
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
        } else {
            String required = "EXPRESSION".equals(type)
                    ? "ADMIN"
                    : String.valueOf(rule.getOrDefault("role", "ADMIN"));
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

    private boolean isMemberOfAny(List<String> tenantIds, String userId) {
        for (String tenantId : tenantIds) {
            if (resolver.resolve(tenantId, userId).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
