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

    @Insert("INSERT INTO workflow_trigger"
            + " (id, definition_id, tenant_id, trigger_type, token, secret, cron_expr,"
            + " timezone, variables_json, version_id, enabled, created_by)"
            + " VALUES (#{id}, #{definitionId}, #{tenantId}, #{triggerType}, #{token},"
            + " #{secret}, #{cronExpr}, #{timezone}, #{variablesJson}, #{versionId},"
            + " #{enabled}, #{createdBy})")
    int insert(Row row);

    @Select("SELECT id, definition_id AS definitionId, tenant_id AS tenantId,"
            + " trigger_type AS triggerType, token, secret, cron_expr AS cronExpr,"
            + " timezone, variables_json AS variablesJson, version_id AS versionId,"
            + " enabled, last_fired_at AS lastFiredAt, last_slot_at AS lastSlotAt,"
            + " created_by AS createdBy,"
            + " created_at AS createdAt, updated_at AS updatedAt"
            + " FROM workflow_trigger WHERE definition_id = #{definitionId}"
            + " AND tenant_id = #{tenantId} ORDER BY created_at")
    List<Row> listByDefinition(@Param("definitionId") String definitionId,
                               @Param("tenantId") String tenantId);

    @Select("SELECT id, definition_id AS definitionId, tenant_id AS tenantId,"
            + " trigger_type AS triggerType, token, secret, cron_expr AS cronExpr,"
            + " timezone, variables_json AS variablesJson, version_id AS versionId,"
            + " enabled, last_fired_at AS lastFiredAt, last_slot_at AS lastSlotAt,"
            + " created_by AS createdBy,"
            + " created_at AS createdAt, updated_at AS updatedAt"
            + " FROM workflow_trigger WHERE id = #{id} AND tenant_id = #{tenantId}")
    Row findByIdAndTenant(@Param("id") String id, @Param("tenantId") String tenantId);

    /** 公开入口按 token 反查：token 唯一，不带租户条件（调用方还没有租户上下文）。 */
    @Select("SELECT id, definition_id AS definitionId, tenant_id AS tenantId,"
            + " trigger_type AS triggerType, token, secret, cron_expr AS cronExpr,"
            + " timezone, variables_json AS variablesJson, version_id AS versionId,"
            + " enabled, last_fired_at AS lastFiredAt, last_slot_at AS lastSlotAt,"
            + " created_by AS createdBy,"
            + " created_at AS createdAt, updated_at AS updatedAt"
            + " FROM workflow_trigger WHERE token = #{token}")
    Row findByToken(@Param("token") String token);

    @Update("UPDATE workflow_trigger SET enabled = #{enabled}, cron_expr = #{cronExpr},"
            + " variables_json = #{variablesJson}"
            + " WHERE id = #{id} AND tenant_id = #{tenantId}")
    int update(@Param("id") String id, @Param("tenantId") String tenantId,
               @Param("enabled") boolean enabled, @Param("cronExpr") String cronExpr,
               @Param("variablesJson") String variablesJson);

    @Update("UPDATE workflow_trigger SET token = #{token}, secret = #{secret}"
            + " WHERE id = #{id} AND tenant_id = #{tenantId}")
    int rotate(@Param("id") String id, @Param("tenantId") String tenantId,
               @Param("token") String token, @Param("secret") String secret);

    /** 同时记下「真正跑的时间」与「代表哪一档」：两者之差就是补偿延迟。 */
    @Update("UPDATE workflow_trigger SET last_fired_at = #{at}, last_slot_at = #{slotAt}"
            + " WHERE id = #{id}")
    int markFired(@Param("id") String id, @Param("at") Timestamp at,
                  @Param("slotAt") Timestamp slotAt);

    @Delete("DELETE FROM workflow_trigger WHERE id = #{id} AND tenant_id = #{tenantId}")
    int delete(@Param("id") String id, @Param("tenantId") String tenantId);

    @Select("SELECT id, definition_id AS definitionId, tenant_id AS tenantId,"
            + " trigger_type AS triggerType, token, secret, cron_expr AS cronExpr,"
            + " timezone, variables_json AS variablesJson, version_id AS versionId,"
            + " enabled, last_fired_at AS lastFiredAt, last_slot_at AS lastSlotAt,"
            + " created_by AS createdBy,"
            + " created_at AS createdAt, updated_at AS updatedAt"
            + " FROM workflow_trigger WHERE trigger_type = 'SCHEDULE' AND enabled = 1")
    List<Row> listEnabledSchedules();

    record Row(String id, String definitionId, String tenantId, String triggerType,
               String token, String secret, String cronExpr, String timezone,
               String variablesJson, String versionId, boolean enabled,
               Timestamp lastFiredAt, Timestamp lastSlotAt, String createdBy,
               Timestamp createdAt, Timestamp updatedAt) {
    }
}
