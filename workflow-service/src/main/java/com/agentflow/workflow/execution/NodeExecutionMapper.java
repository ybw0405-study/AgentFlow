package com.agentflow.workflow.execution;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NodeExecutionMapper {

    /** 投影器 upsert：唯一键 (execution_id, node_key, attempt)。 */
    @Insert("INSERT INTO node_execution"
            + " (id, execution_id, node_key, display_order, status, attempt, input_digest,"
            + "  output_digest, token_used, latency_ms, model, retry_count, error_code,"
            + "  error_message, started_at, finished_at)"
            + " VALUES (#{id}, #{executionId}, #{nodeKey}, #{displayOrder}, #{status},"
            + " #{attempt}, #{inputDigest}, #{outputDigest}, #{tokenUsed}, #{latencyMs},"
            + " #{model}, #{retryCount}, #{errorCode}, #{errorMessage}, #{startedAt},"
            + " #{finishedAt})"
            + " ON DUPLICATE KEY UPDATE"
            + " status = VALUES(status),"
            + " input_digest = IFNULL(VALUES(input_digest), input_digest),"
            + " output_digest = IFNULL(VALUES(output_digest), output_digest),"
            + " token_used = VALUES(token_used),"
            + " latency_ms = IFNULL(VALUES(latency_ms), latency_ms),"
            + " model = IFNULL(VALUES(model), model),"
            + " retry_count = VALUES(retry_count),"
            + " error_code = IFNULL(VALUES(error_code), error_code),"
            + " error_message = IFNULL(VALUES(error_message), error_message),"
            + " started_at = IFNULL(VALUES(started_at), started_at),"
            + " finished_at = IFNULL(VALUES(finished_at), finished_at)")
    int upsert(NodeRow row);

    @Select("SELECT seq, id, execution_id AS executionId, node_key AS nodeKey,"
            + " display_order AS displayOrder, status, attempt, input_digest AS inputDigest,"
            + " output_digest AS outputDigest, token_used AS tokenUsed,"
            + " latency_ms AS latencyMs, model, retry_count AS retryCount,"
            + " error_code AS errorCode,"
            + " error_message AS errorMessage,"
            + " started_at AS startedAt, finished_at AS finishedAt"
            + " FROM node_execution WHERE execution_id = #{executionId}"
            + " ORDER BY display_order, attempt")
    List<NodeRow> listByExecution(@Param("executionId") String executionId);

    /** 按模型分解（执行侧）：模型记在节点上，租户与时间在所属执行上。 */
    @Select("SELECT COALESCE(n.model,'') AS model, COUNT(*) AS cnt,"
            + " COALESCE(SUM(n.token_used),0) AS tokens"
            + " FROM node_execution n JOIN workflow_execution e ON e.id = n.execution_id"
            + " WHERE e.tenant_id = #{tenantId} AND e.created_at >= #{from}"
            + " GROUP BY COALESCE(n.model,'')")
    List<ModelUsageRow> usageByModel(@Param("tenantId") String tenantId,
                                     @Param("from") String from);

    record ModelUsageRow(String model, long cnt, long tokens) {
    }

    record NodeRow(long seq, String id, String executionId, String nodeKey, int displayOrder,
                   String status, int attempt, String inputDigest, String outputDigest,
                   int tokenUsed, Integer latencyMs, String model, int retryCount,
                   String errorCode, String errorMessage, Timestamp startedAt,
                   Timestamp finishedAt) {
    }
}
