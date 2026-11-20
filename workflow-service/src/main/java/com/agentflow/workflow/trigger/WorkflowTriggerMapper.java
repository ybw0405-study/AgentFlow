package com.agentflow.workflow.trigger;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 工作流外部触发（webhook / cron）的持久化。 */
@Mapper
public interface WorkflowTriggerMapper {

    /**
     * 四个查询共用同一组列：加字段时只改这一处。
     *
     * <p>注解里的字符串必须是编译期常量，所以它是接口常量而不是私有常量。
     */
    String COLUMNS = "SELECT id, definition_id AS definitionId, tenant_id AS tenantId,"
            + " trigger_type AS triggerType, token, secret, cron_expr AS cronExpr,"
            + " timezone, variables_json AS variablesJson, version_id AS versionId,"
            + " enabled, misfire_policy AS misfirePolicy, last_fired_at AS lastFiredAt,"
            + " last_slot_at AS lastSlotAt, last_outcome AS lastOutcome,"
            + " created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt"
            + " FROM workflow_trigger";

    @Insert("INSERT INTO workflow_trigger"
            + " (id, definition_id, tenant_id, trigger_type, token, secret, cron_expr,"
            + " timezone, variables_json, version_id, enabled, misfire_policy, created_by)"
            + " VALUES (#{id}, #{definitionId}, #{tenantId}, #{triggerType}, #{token},"
            + " #{secret}, #{cronExpr}, #{timezone}, #{variablesJson}, #{versionId},"
            + " #{enabled}, #{misfirePolicy}, #{createdBy})")
    int insert(Row row);

    @Select(COLUMNS + " WHERE definition_id = #{definitionId}"
            + " AND tenant_id = #{tenantId} ORDER BY created_at")
    List<Row> listByDefinition(@Param("definitionId") String definitionId,
                               @Param("tenantId") String tenantId);

    @Select(COLUMNS + " WHERE id = #{id} AND tenant_id = #{tenantId}")
    Row findByIdAndTenant(@Param("id") String id, @Param("tenantId") String tenantId);

    /** 公开入口按 token 反查：token 唯一，不带租户条件（调用方还没有租户上下文）。 */
    @Select(COLUMNS + " WHERE token = #{token}")
    Row findByToken(@Param("token") String token);

    @Update("UPDATE workflow_trigger SET enabled = #{enabled}, cron_expr = #{cronExpr},"
            + " misfire_policy = #{misfirePolicy}, variables_json = #{variablesJson}"
            + " WHERE id = #{id} AND tenant_id = #{tenantId}")
    int update(@Param("id") String id, @Param("tenantId") String tenantId,
               @Param("enabled") boolean enabled, @Param("cronExpr") String cronExpr,
               @Param("misfirePolicy") String misfirePolicy,
               @Param("variablesJson") String variablesJson);

    @Update("UPDATE workflow_trigger SET token = #{token}, secret = #{secret}"
            + " WHERE id = #{id} AND tenant_id = #{tenantId}")
    int rotate(@Param("id") String id, @Param("tenantId") String tenantId,
               @Param("token") String token, @Param("secret") String secret);

    /** 同时记下「真正跑的时间」与「代表哪一档」：两者之差就是补偿延迟。 */
    @Update("UPDATE workflow_trigger SET last_fired_at = #{at}, last_slot_at = #{slotAt},"
            + " last_outcome = 'FIRE' WHERE id = #{id}")
    int markFired(@Param("id") String id, @Param("at") Timestamp at,
                  @Param("slotAt") Timestamp slotAt);

    /**
     * 跳过：只推游标，不动 {@code last_fired_at}。
     *
     * <p>{@code last_fired_at} 是「真的跑过一次」的事实，界面拿它当「上次触发」显示；
     * 跳过不是触发，写进去会让页面撒谎。扫描的基准值取两者较大者
     * （见 {@link TriggerService#dueSlots}），所以游标照样能前进。
     */
    @Update("UPDATE workflow_trigger SET last_slot_at = #{slotAt}, last_outcome = 'SKIP'"
            + " WHERE id = #{id}")
    int markSkipped(@Param("id") String id, @Param("slotAt") Timestamp slotAt);

    @Delete("DELETE FROM workflow_trigger WHERE id = #{id} AND tenant_id = #{tenantId}")
    int delete(@Param("id") String id, @Param("tenantId") String tenantId);

    @Select(COLUMNS + " WHERE trigger_type = 'SCHEDULE' AND enabled = 1")
    List<Row> listEnabledSchedules();

    record Row(String id, String definitionId, String tenantId, String triggerType,
               String token, String secret, String cronExpr, String timezone,
               String variablesJson, String versionId, boolean enabled, String misfirePolicy,
               Timestamp lastFiredAt, Timestamp lastSlotAt, String lastOutcome,
               String createdBy, Timestamp createdAt, Timestamp updatedAt) {
    }
}
