package com.agentflow.workflow.execution;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 试跑用量（preview_usage）：试跑不产生执行记录，token 却真的被上游扣掉了。
 *
 * <p>只写不更不改：用量是审计数据，写错了也只能追加更正行，不能原地改数。
 * 日期一律由 SQL 格式化成字符串返回，避免 JDBC 时区把「今天的柱子」挪到昨天。
 */
@Mapper
public interface PreviewUsageMapper {

    @Insert("INSERT INTO preview_usage (id, tenant_id, definition_id, node_key, actor_id,"
            + " status, model, node_count, token_used, latency_ms) VALUES (#{id}, #{tenantId},"
            + " #{definitionId}, #{nodeKey}, #{actorId}, #{status}, #{model}, #{nodeCount},"
            + " #{tokenUsed}, #{latencyMs})")
    int insert(PreviewUsageRow row);

    @Select("SELECT COUNT(*) AS cnt, COALESCE(SUM(token_used),0) AS tokens,"
            + " COALESCE(SUM(node_count),0) AS nodes FROM preview_usage"
            + " WHERE tenant_id = #{tenantId} AND created_at >= #{from}")
    PreviewTotalsRow totals(@Param("tenantId") String tenantId, @Param("from") String from);

    @Select("SELECT definition_id AS definitionId, COUNT(*) AS cnt,"
            + " COALESCE(SUM(token_used),0) AS tokens FROM preview_usage"
            + " WHERE tenant_id = #{tenantId} AND created_at >= #{from}"
            + " GROUP BY definition_id")
    List<PreviewDefinitionRow> byDefinition(@Param("tenantId") String tenantId,
                                            @Param("from") String from);

    @Select("SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day,"
            + " COALESCE(SUM(token_used),0) AS tokens FROM preview_usage"
            + " WHERE tenant_id = #{tenantId} AND created_at >= #{from}"
            + " GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')")
    List<PreviewDailyRow> daily(@Param("tenantId") String tenantId, @Param("from") String from);

    /** 最近的试跑明细：谁在哪个节点上花了多少 token，用于解释账面的增量。 */
    @Select("SELECT p.id AS id, p.definition_id AS definitionId, d.name AS definitionName,"
            + " p.node_key AS nodeKey, p.actor_id AS actorId, p.status, p.model AS model,"
            + " p.token_used AS tokenUsed, p.latency_ms AS latencyMs,"
            + " DATE_FORMAT(p.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt"
            + " FROM preview_usage p LEFT JOIN workflow_definition d ON d.id = p.definition_id"
            + " WHERE p.tenant_id = #{tenantId} ORDER BY p.seq DESC LIMIT #{limit}")
    List<RecentPreviewRow> recent(@Param("tenantId") String tenantId,
                                  @Param("limit") int limit);

    /** 本月试跑计进账本的部分：用来把额度条切成「执行 / 试跑」两段。 */
    @Select("SELECT COALESCE(SUM(node_count),0) AS calls, COALESCE(SUM(token_used),0) AS tokens"
            + " FROM preview_usage WHERE tenant_id = #{tenantId}"
            + " AND DATE_FORMAT(created_at, '%Y-%m') = #{period}")
    PreviewMonthRow monthToDate(@Param("tenantId") String tenantId,
                                @Param("period") String period);

    /** 按模型分解（试跑侧）：正式执行的模型来自 node_execution，两张表在服务层合并。 */
    @Select("SELECT COALESCE(model,'') AS model, COUNT(*) AS cnt,"
            + " COALESCE(SUM(token_used),0) AS tokens FROM preview_usage"
            + " WHERE tenant_id = #{tenantId} AND created_at >= #{from}"
            + " GROUP BY COALESCE(model,'')")
    List<PreviewModelRow> byModel(@Param("tenantId") String tenantId,
                                  @Param("from") String from);

    record PreviewUsageRow(String id, String tenantId, String definitionId, String nodeKey,
                           String actorId, String status, String model, int nodeCount,
                           int tokenUsed, Integer latencyMs) {
    }

    record PreviewTotalsRow(long cnt, long tokens, long nodes) {
    }

    record PreviewDefinitionRow(String definitionId, long cnt, long tokens) {
    }

    record PreviewDailyRow(String day, long tokens) {
    }

    /** 本账期各租户的试跑合计：一次查完，避免按租户逐个查。 */
    @Select("SELECT tenant_id AS tenantId, COALESCE(SUM(node_count),0) AS calls,"
            + " COALESCE(SUM(token_used),0) AS tokens FROM preview_usage"
            + " WHERE DATE_FORMAT(created_at, '%Y-%m') = #{period} GROUP BY tenant_id")
    List<PreviewTenantMonthRow> monthByTenant(@Param("period") String period);

    /** 平台级按模型分解（试跑侧），与执行侧的 {@code usageByModelAll} 在服务层合并。 */
    @Select("SELECT COALESCE(model,'') AS model, COUNT(*) AS cnt,"
            + " COALESCE(SUM(token_used),0) AS tokens, COUNT(DISTINCT tenant_id) AS tenants"
            + " FROM preview_usage WHERE created_at >= #{from} GROUP BY COALESCE(model,'')")
    List<PlatformPreviewModelRow> byModelAll(@Param("from") String from);

    record PreviewMonthRow(long calls, long tokens) {
    }

    record PreviewTenantMonthRow(String tenantId, long calls, long tokens) {
    }

    record PlatformPreviewModelRow(String model, long cnt, long tokens, long tenants) {
    }

    record PreviewModelRow(String model, long cnt, long tokens) {
    }

    record RecentPreviewRow(String id, String definitionId, String definitionName, String nodeKey,
                            String actorId, String status, String model, int tokenUsed,
                            Integer latencyMs, String createdAt) {
    }
}
