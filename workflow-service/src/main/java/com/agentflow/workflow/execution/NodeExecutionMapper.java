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
            + "  output_digest, token_used, latency_ms, retry_count, error_code,"
            + "  started_at, finished_at)"
            + " VALUES (#{id}, #{executionId}, #{nodeKey}, #{displayOrder}, #{status},"
            + " #{attempt}, #{inputDigest}, #{outputDigest}, #{tokenUsed}, #{latencyMs},"
            + " #{retryCount}, #{errorCode}, #{startedAt}, #{finishedAt})"
            + " ON DUPLICATE KEY UPDATE"
            + " status = VALUES(status),"
            + " input_digest = IFNULL(VALUES(input_digest), input_digest),"
            + " output_digest = IFNULL(VALUES(output_digest), output_digest),"
            + " token_used = VALUES(token_used),"
            + " latency_ms = IFNULL(VALUES(latency_ms), latency_ms),"
            + " retry_count = VALUES(retry_count),"
            + " error_code = IFNULL(VALUES(error_code), error_code),"
            + " started_at = IFNULL(VALUES(started_at), started_at),"
            + " finished_at = IFNULL(VALUES(finished_at), finished_at)")
    int upsert(NodeRow row);

    @Select("SELECT seq, id, execution_id AS executionId, node_key AS nodeKey,"
            + " display_order AS displayOrder, status, attempt, input_digest AS inputDigest,"
            + " output_digest AS outputDigest, token_used AS tokenUsed,"
            + " latency_ms AS latencyMs, retry_count AS retryCount, error_code AS errorCode,"
            + " started_at AS startedAt, finished_at AS finishedAt"
            + " FROM node_execution WHERE execution_id = #{executionId}"
            + " ORDER BY display_order, attempt")
    List<NodeRow> listByExecution(@Param("executionId") String executionId);

    record NodeRow(long seq, String id, String executionId, String nodeKey, int displayOrder,
                   String status, int attempt, String inputDigest, String outputDigest,
                   int tokenUsed, Integer latencyMs, int retryCount, String errorCode,
                   Timestamp startedAt, Timestamp finishedAt) {
    }
}
