package com.agentflow.workflow.gate;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 审批待办（GateSuspended 投影）。approvers_json 是挂起瞬间冻结的审批人快照。 */
@Mapper
public interface GateTaskMapper {

    @Insert("INSERT INTO gate_task"
            + " (id, execution_id, node_key, tenant_id, definition_id, prompt,"
            + "  approvers_json, approver_rule_json, status, timeout_at, default_branch)"
            + " VALUES (#{id}, #{executionId}, #{nodeKey}, #{tenantId}, #{definitionId},"
            + "  #{prompt}, #{approversJson}, #{approverRuleJson}, 'PENDING',"
            + "  #{timeoutAt}, #{defaultBranch})"
            + " ON DUPLICATE KEY UPDATE prompt = VALUES(prompt)")
    int insert(GateRow row);

    String COLUMNS = "seq, id, execution_id AS executionId, node_key AS nodeKey,"
            + " tenant_id AS tenantId, definition_id AS definitionId, prompt,"
            + " approvers_json AS approversJson, approver_rule_json AS approverRuleJson,"
            + " status, decision, resolved_by AS resolvedBy, comment,"
            + " timeout_at AS timeoutAt, default_branch AS defaultBranch,"
            + " created_at AS createdAt";

    @Select("SELECT " + COLUMNS + " FROM gate_task"
            + " WHERE execution_id = #{executionId} AND node_key = #{nodeKey}")
    GateRow findOne(@Param("executionId") String executionId, @Param("nodeKey") String nodeKey);

    /** 我的待办：只查我空间里、审批人快照包含我、仍 PENDING 的 gate（带执行状态）。 */
    @Select("SELECT g.id, g.execution_id AS executionId, g.node_key AS nodeKey, g.prompt,"
            + " g.approvers_json AS approversJson, g.status, g.timeout_at AS timeoutAt,"
            + " g.default_branch AS defaultBranch, g.created_at AS createdAt,"
            + " e.status AS executionStatus, e.definition_id AS definitionId,"
            + " e.triggered_by AS triggeredBy"
            + " FROM gate_task g JOIN workflow_execution e ON e.id = g.execution_id"
            + " WHERE g.tenant_id = #{spaceId} AND g.status = 'PENDING'"
            + " AND JSON_CONTAINS(g.approvers_json, JSON_QUOTE(#{userId}))"
            + " ORDER BY g.seq DESC LIMIT #{limit}")
    List<InboxRow> inbox(@Param("spaceId") String spaceId,
                         @Param("userId") String userId,
                         @Param("limit") int limit);

    @Select("SELECT " + COLUMNS + " FROM gate_task WHERE execution_id = #{executionId}"
            + " ORDER BY seq")
    List<GateRow> listByExecution(@Param("executionId") String executionId);

    @Update("UPDATE gate_task SET status = #{status}, decision = #{decision},"
            + " resolved_by = #{resolvedBy}, comment = #{comment}"
            + " WHERE execution_id = #{executionId} AND node_key = #{nodeKey}"
            + " AND status = 'PENDING'")
    int resolve(@Param("executionId") String executionId, @Param("nodeKey") String nodeKey,
                @Param("status") String status, @Param("decision") String decision,
                @Param("resolvedBy") String resolvedBy, @Param("comment") String comment);

    @Update("UPDATE gate_task SET status = 'CANCELLED'"
            + " WHERE execution_id = #{executionId} AND status = 'PENDING'")
    int cancelByExecution(@Param("executionId") String executionId);

    record InboxRow(String id, String executionId, String nodeKey, String prompt,
                    String approversJson, String status, Timestamp timeoutAt,
                    String defaultBranch, Timestamp createdAt, String executionStatus,
                    String definitionId, String triggeredBy) {
    }

    record GateRow(long seq, String id, String executionId, String nodeKey, String tenantId,
                   String definitionId, String prompt, String approversJson,
                   String approverRuleJson, String status, String decision, String resolvedBy,
                   String comment, Timestamp timeoutAt, String defaultBranch,
                   Timestamp createdAt) {
    }
}
