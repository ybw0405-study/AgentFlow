package com.agentflow.workflow.execution;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkflowExecutionMapper {

    @Insert("INSERT INTO workflow_execution"
            + " (id, definition_id, version_id, tenant_id, triggered_by, status,"
            + "  idempotency_key, trace_id, token_budget, max_steps, deadline_at)"
            + " VALUES (#{id}, #{definitionId}, #{versionId}, #{tenantId}, #{triggeredBy},"
            + " 'PENDING', #{idempotencyKey}, #{traceId}, #{tokenBudget}, #{maxSteps},"
            + " #{deadlineAt})")
    int insert(ExecutionRow row);

    @Select("SELECT seq, id, definition_id AS definitionId, version_id AS versionId,"
            + " tenant_id AS tenantId, triggered_by AS triggeredBy, status, idempotency_key"
            + " AS idempotencyKey, trace_id AS traceId, token_budget AS tokenBudget,"
            + " max_steps AS maxSteps, deadline_at AS deadlineAt, token_used AS tokenUsed,"
            + " steps_used AS stepsUsed, error_code AS errorCode, started_at AS startedAt,"
            + " finished_at AS finishedAt, created_at AS createdAt"
            + " FROM workflow_execution"
            + " WHERE id = #{id} AND tenant_id = #{tenantId}")
    ExecutionRow findByIdAndTenant(@Param("id") String id, @Param("tenantId") String tenantId);

    @Select("SELECT seq, id, definition_id AS definitionId, version_id AS versionId,"
            + " tenant_id AS tenantId, triggered_by AS triggeredBy, status, idempotency_key"
            + " AS idempotencyKey, trace_id AS traceId, token_budget AS tokenBudget,"
            + " max_steps AS maxSteps, deadline_at AS deadlineAt, token_used AS tokenUsed,"
            + " steps_used AS stepsUsed, error_code AS errorCode, started_at AS startedAt,"
            + " finished_at AS finishedAt, created_at AS createdAt"
            + " FROM workflow_execution"
            + " WHERE tenant_id = #{tenantId} AND idempotency_key = #{ikey}")
    ExecutionRow findByIdempotency(@Param("tenantId") String tenantId,
                                   @Param("ikey") String ikey);

    @Select("<script>"
            + "SELECT seq, id, definition_id AS definitionId, version_id AS versionId,"
            + " tenant_id AS tenantId, triggered_by AS triggeredBy, status, idempotency_key"
            + " AS idempotencyKey, trace_id AS traceId, token_budget AS tokenBudget,"
            + " max_steps AS maxSteps, deadline_at AS deadlineAt, token_used AS tokenUsed,"
            + " steps_used AS stepsUsed, error_code AS errorCode, started_at AS startedAt,"
            + " finished_at AS finishedAt, created_at AS createdAt"
            + " FROM workflow_execution"
            + " WHERE tenant_id = #{tenantId} AND seq &gt; #{afterSeq}"
            + "<if test='status != null'> AND status = #{status}</if>"
            + "<if test='definitionId != null'> AND definition_id = #{definitionId}</if>"
            + " ORDER BY seq DESC LIMIT #{limit}"
            + "</script>")
    List<ExecutionRow> listByTenant(@Param("tenantId") String tenantId,
                                    @Param("status") String status,
                                    @Param("definitionId") String definitionId,
                                    @Param("afterSeq") long afterSeq,
                                    @Param("limit") int limit);

    /** 投影器写入（事件是唯一真相，此处只是查询索引）。按事件类型定向更新。 */
    @Update("UPDATE workflow_execution SET status = #{status}, started_at = IFNULL(started_at, NOW(3))"
            + " WHERE id = #{id} AND status = 'PENDING'")
    int markRunning(@Param("id") String id, @Param("status") String status);

    @Update("UPDATE workflow_execution SET status = #{status}, token_used = #{tokenUsed},"
            + " steps_used = #{stepsUsed}, error_code = #{errorCode}, finished_at = NOW(3)"
            + " WHERE id = #{id}")
    int markTerminal(@Param("id") String id, @Param("status") String status,
                     @Param("tokenUsed") int tokenUsed, @Param("stepsUsed") int stepsUsed,
                     @Param("errorCode") String errorCode);

    /** 投影器：GateSuspended → 挂起（不落终态，finished_at 保持为空）。 */
    @Update("UPDATE workflow_execution SET status = 'SUSPENDED'"
            + " WHERE id = #{id} AND status = 'RUNNING'")
    int markSuspended(@Param("id") String id);

    /** 投影器：GateResolved → 回到 RUNNING（由裁决回调续跑）。 */
    @Update("UPDATE workflow_execution SET status = 'RUNNING'"
            + " WHERE id = #{id} AND status = 'SUSPENDED'")
    int markResumed(@Param("id") String id);

    /** 评测轮询：执行状态与耗时/token（都来自投影表，不额外打引擎）。 */
    @Select("SELECT status, token_used AS tokenUsed, error_code AS errorCode,"
            + " started_at AS startedAt, finished_at AS finishedAt"
            + " FROM workflow_execution WHERE id = #{id}")
    EvalExecutionRow findForEval(@Param("id") String id);

    /** 投影器无租户上下文，按主键取投影所需的最小信息。 */
    @Select("SELECT id AS id, tenant_id AS tenantId, definition_id AS definitionId,"
            + " version_id AS versionId FROM workflow_execution WHERE id = #{id}")
    ExecutionBrief findBrief(@Param("id") String id);

    @Update("UPDATE workflow_execution SET status = #{status}, error_code = #{errorCode}"
            + " WHERE id = #{id}")
    int markFailedDispatch(@Param("id") String id, @Param("status") String status,
                           @Param("errorCode") String errorCode);

    /**
     * 看护候选：仍停在 PENDING / RUNNING，却「按承诺早该结束」的执行。
     *
     * <p>两个条件各自对应一种卡死：{@code deadline_at} 已过却还没落终态（引擎侧超时事件
     * 没到，或者引擎已经不在）；或者一直是 PENDING 且创建已久（派发在「插入」与「提交」
     * 之间断了，这一行永远不会有人来推进）。两条都有余量阈值，由调用方传入，SQL 不猜。
     *
     * <p>按 seq 升序取：最老的先收口，积压时先解决躺得最久的那批。
     */
    @Select("SELECT seq, id, definition_id AS definitionId, version_id AS versionId,"
            + " tenant_id AS tenantId, triggered_by AS triggeredBy, status, idempotency_key"
            + " AS idempotencyKey, trace_id AS traceId, token_budget AS tokenBudget,"
            + " max_steps AS maxSteps, deadline_at AS deadlineAt, token_used AS tokenUsed,"
            + " steps_used AS stepsUsed, error_code AS errorCode, started_at AS startedAt,"
            + " finished_at AS finishedAt, created_at AS createdAt"
            + " FROM workflow_execution"
            + " WHERE status IN ('PENDING', 'RUNNING')"
            + " AND ((deadline_at IS NOT NULL AND deadline_at < #{deadlineCutoff})"
            + "   OR (status = 'PENDING' AND created_at < #{pendingCutoff}))"
            + " ORDER BY seq ASC LIMIT #{limit}")
    List<ExecutionRow> findStale(@Param("deadlineCutoff") Timestamp deadlineCutoff,
                                 @Param("pendingCutoff") Timestamp pendingCutoff,
                                 @Param("limit") int limit);

    /**
     * 管理端：本账期内各租户的执行数与失败数。
     *
     * <p>这里刻意按 {@code created_at} 卡区间而不是「全部时间」：成本中心整页都是账期口径，
     * 混进一张全时段的表会让人把「这个月失败 8 次」读成「一共失败 8 次」。
     */
    @Select("SELECT tenant_id AS tenantId, status, COUNT(*) AS cnt,"
            + " COALESCE(SUM(token_used),0) AS tokens"
            + " FROM workflow_execution WHERE created_at >= #{from}"
            + " GROUP BY tenant_id, status")
    List<UsageRow> usageByTenantSince(@Param("from") String from);

    /** 租户自己的用量（只算正式执行）：与试跑用量分开统计，避免「调试把额度吃光」看不出来。 */
    @Select("SELECT COUNT(*) AS cnt, COALESCE(SUM(token_used),0) AS tokens"
            + " FROM workflow_execution WHERE tenant_id = #{tenantId} AND created_at >= #{from}")
    TotalsRow usageTotals(@Param("tenantId") String tenantId, @Param("from") String from);

    @Select("SELECT definition_id AS definitionId, COUNT(*) AS cnt,"
            + " COALESCE(SUM(token_used),0) AS tokens FROM workflow_execution"
            + " WHERE tenant_id = #{tenantId} AND created_at >= #{from} GROUP BY definition_id")
    List<DefinitionUsageRow> usageByDefinition(@Param("tenantId") String tenantId,
                                               @Param("from") String from);

    @Select("SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day,"
            + " COALESCE(SUM(token_used),0) AS tokens FROM workflow_execution"
            + " WHERE tenant_id = #{tenantId} AND created_at >= #{from}"
            + " GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')")
    List<DailyUsageRow> usageDaily(@Param("tenantId") String tenantId,
                                   @Param("from") String from);

    record TotalsRow(long cnt, long tokens) {
    }

    record DefinitionUsageRow(String definitionId, long cnt, long tokens) {
    }

    record DailyUsageRow(String day, long tokens) {
    }

    record ExecutionRow(long seq, String id, String definitionId, String versionId,
                        String tenantId, String triggeredBy, String status,
                        String idempotencyKey, String traceId, Integer tokenBudget,
                        Integer maxSteps, Timestamp deadlineAt, int tokenUsed, int stepsUsed,
                        String errorCode, Timestamp startedAt, Timestamp finishedAt,
                        Timestamp createdAt) {
    }

    record UsageRow(String tenantId, String status, long cnt, long tokens) {
    }

    record ExecutionBrief(String id, String tenantId, String definitionId, String versionId) {
    }

    record EvalExecutionRow(String status, int tokenUsed, String errorCode,
                            Timestamp startedAt, Timestamp finishedAt) {
    }
}
