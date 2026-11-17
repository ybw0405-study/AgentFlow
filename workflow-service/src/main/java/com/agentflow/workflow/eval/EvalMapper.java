package com.agentflow.workflow.eval;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 评测集 / 运行 / A-B 实验的持久化（V006）。 */
@Mapper
public interface EvalMapper {

    // ---------- 数据集 ----------

    @Insert("INSERT INTO eval_dataset (id, tenant_id, name, description, created_by)"
            + " VALUES (#{id}, #{tenantId}, #{name}, #{description}, #{createdBy})")
    int insertDataset(DatasetRow row);

    @Select("SELECT id, tenant_id AS tenantId, name, description, created_by AS createdBy,"
            + " created_at AS createdAt FROM eval_dataset"
            + " WHERE tenant_id = #{tenantId} AND deleted_at IS NULL ORDER BY created_at DESC")
    List<DatasetRow> listDatasets(@Param("tenantId") String tenantId);

    @Select("SELECT id, tenant_id AS tenantId, name, description, created_by AS createdBy,"
            + " created_at AS createdAt FROM eval_dataset"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    DatasetRow findDataset(@Param("id") String id, @Param("tenantId") String tenantId);

    @Update("UPDATE eval_dataset SET deleted_at = NOW(3)"
            + " WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int deleteDataset(@Param("id") String id, @Param("tenantId") String tenantId);

    // ---------- 用例 ----------

    @Insert("INSERT INTO eval_case (id, dataset_id, tenant_id, name, input_json,"
            + " expected_json, assertion_json, enabled)"
            + " VALUES (#{id}, #{datasetId}, #{tenantId}, #{name}, #{inputJson},"
            + "  #{expectedJson}, #{assertionJson}, #{enabled})")
    int insertCase(CaseRow row);

    @Select("SELECT id, dataset_id AS datasetId, tenant_id AS tenantId, name,"
            + " input_json AS inputJson, expected_json AS expectedJson,"
            + " assertion_json AS assertionJson, enabled, created_at AS createdAt"
            + " FROM eval_case WHERE dataset_id = #{datasetId} AND tenant_id = #{tenantId}"
            + " ORDER BY created_at")
    List<CaseRow> listCases(@Param("datasetId") String datasetId,
                            @Param("tenantId") String tenantId);

    @Select("SELECT id, dataset_id AS datasetId, tenant_id AS tenantId, name,"
            + " input_json AS inputJson, expected_json AS expectedJson,"
            + " assertion_json AS assertionJson, enabled, created_at AS createdAt"
            + " FROM eval_case WHERE id = #{id} AND tenant_id = #{tenantId}")
    CaseRow findCase(@Param("id") String id, @Param("tenantId") String tenantId);

    @Update("DELETE FROM eval_case WHERE id = #{id} AND tenant_id = #{tenantId}")
    int deleteCase(@Param("id") String id, @Param("tenantId") String tenantId);

    // ---------- 运行 ----------

    @Insert("INSERT INTO eval_run (id, dataset_id, tenant_id, definition_id, version_id,"
            + " status, total, passed, failed, experiment_id, variant, created_by, started_at)"
            + " VALUES (#{id}, #{datasetId}, #{tenantId}, #{definitionId}, #{versionId},"
            + "  'RUNNING', #{total}, 0, 0, #{experimentId}, #{variant}, #{createdBy}, NOW(3))")
    int insertRun(RunRow row);

    String RUN_COLUMNS = "id, dataset_id AS datasetId, tenant_id AS tenantId,"
            + " definition_id AS definitionId, version_id AS versionId, status, total, passed,"
            + " failed, experiment_id AS experimentId, variant, error_message AS errorMessage,"
            + " created_by AS createdBy, started_at AS startedAt, finished_at AS finishedAt,"
            + " created_at AS createdAt";

    @Select("SELECT " + RUN_COLUMNS + " FROM eval_run"
            + " WHERE id = #{id} AND tenant_id = #{tenantId}")
    RunRow findRun(@Param("id") String id, @Param("tenantId") String tenantId);

    @Select("SELECT " + RUN_COLUMNS + " FROM eval_run"
            + " WHERE dataset_id = #{datasetId} AND tenant_id = #{tenantId}"
            + " ORDER BY created_at DESC LIMIT #{limit}")
    List<RunRow> listRuns(@Param("datasetId") String datasetId,
                          @Param("tenantId") String tenantId, @Param("limit") int limit);

    @Select("SELECT " + RUN_COLUMNS + " FROM eval_run WHERE status = 'RUNNING'"
            + " ORDER BY created_at LIMIT #{limit}")
    List<RunRow> runningRuns(@Param("limit") int limit);

    @Update("UPDATE eval_run SET status = #{status}, total = #{total}, passed = #{passed},"
            + " failed = #{failed}, error_message = #{errorMessage},"
            + " finished_at = IF(#{status} = 'RUNNING', NULL, NOW(3)) WHERE id = #{id}")
    int updateRunStats(@Param("id") String id, @Param("status") String status,
                       @Param("total") int total, @Param("passed") int passed,
                       @Param("failed") int failed, @Param("errorMessage") String errorMessage);

    @Update("UPDATE eval_run SET passed = #{passed}, failed = #{failed} WHERE id = #{id}")
    int updateRunCounters(@Param("id") String id, @Param("passed") int passed,
                          @Param("failed") int failed);

    // ---------- 用例结果 ----------

    @Insert("INSERT INTO eval_case_result (id, run_id, case_id, case_name, execution_id,"
            + " status, passed, error_message)"
            + " VALUES (#{id}, #{runId}, #{caseId}, #{caseName}, #{executionId},"
            + "  #{status}, #{passed}, #{errorMessage})")
    int insertResult(ResultRow row);

    @Select("SELECT id, run_id AS runId, case_id AS caseId, case_name AS caseName,"
            + " execution_id AS executionId, status, passed, actual_json AS actualJson,"
            + " latency_ms AS latencyMs, token_used AS tokenUsed,"
            + " error_message AS errorMessage FROM eval_case_result"
            + " WHERE run_id = #{runId} ORDER BY created_at")
    List<ResultRow> listResults(@Param("runId") String runId);

    @Select("SELECT id, run_id AS runId, case_id AS caseId, case_name AS caseName,"
            + " execution_id AS executionId, status, passed, actual_json AS actualJson,"
            + " latency_ms AS latencyMs, token_used AS tokenUsed,"
            + " error_message AS errorMessage FROM eval_case_result"
            + " WHERE run_id = #{runId} AND status = 'PENDING' AND execution_id IS NOT NULL")
    List<ResultRow> openResults(@Param("runId") String runId);

    @Update("UPDATE eval_case_result SET status = #{status}, passed = #{passed},"
            + " actual_json = #{actualJson}, latency_ms = #{latencyMs},"
            + " token_used = #{tokenUsed}, error_message = #{errorMessage} WHERE id = #{id}")
    int updateResult(@Param("id") String id, @Param("status") String status,
                     @Param("passed") Boolean passed, @Param("actualJson") String actualJson,
                     @Param("latencyMs") Integer latencyMs, @Param("tokenUsed") Integer tokenUsed,
                     @Param("errorMessage") String errorMessage);

    @Select("SELECT COUNT(*) AS total,"
            + " COALESCE(SUM(CASE WHEN passed = 1 THEN 1 ELSE 0 END), 0) AS passed,"
            + " COALESCE(SUM(CASE WHEN passed = 0 OR passed IS NULL THEN 1 ELSE 0 END), 0)"
            + "   AS failed,"
            + " AVG(latency_ms) AS avgLatency, AVG(token_used) AS avgTokens"
            + " FROM eval_case_result WHERE run_id = #{runId}")
    RunStats stats(@Param("runId") String runId);

    // ---------- A/B 实验 ----------

    @Insert("INSERT INTO ab_experiment (id, tenant_id, definition_id, dataset_id, name,"
            + " version_a, version_b, run_a, run_b, status, created_by)"
            + " VALUES (#{id}, #{tenantId}, #{definitionId}, #{datasetId}, #{name},"
            + "  #{versionA}, #{versionB}, #{runA}, #{runB}, 'RUNNING', #{createdBy})")
    int insertExperiment(ExperimentRow row);

    @Update("UPDATE ab_experiment SET run_a = #{runA}, run_b = #{runB} WHERE id = #{id}")
    int bindRuns(@Param("id") String id, @Param("runA") String runA, @Param("runB") String runB);

    @Select("SELECT id, tenant_id AS tenantId, definition_id AS definitionId,"
            + " dataset_id AS datasetId, name, version_a AS versionA, version_b AS versionB,"
            + " run_a AS runA, run_b AS runB, status, summary_json AS summaryJson,"
            + " created_by AS createdBy, created_at AS createdAt,"
            + " finished_at AS finishedAt"
            + " FROM ab_experiment WHERE id = #{id} AND tenant_id = #{tenantId}")
    ExperimentRow findExperiment(@Param("id") String id, @Param("tenantId") String tenantId);

    @Select("SELECT id, tenant_id AS tenantId, definition_id AS definitionId,"
            + " dataset_id AS datasetId, name, version_a AS versionA, version_b AS versionB,"
            + " run_a AS runA, run_b AS runB, status, summary_json AS summaryJson,"
            + " created_by AS createdBy, created_at AS createdAt,"
            + " finished_at AS finishedAt"
            + " FROM ab_experiment WHERE tenant_id = #{tenantId}"
            + " AND (#{definitionId} IS NULL OR definition_id = #{definitionId})"
            + " ORDER BY created_at DESC LIMIT #{limit}")
    List<ExperimentRow> listExperiments(@Param("tenantId") String tenantId,
                                        @Param("definitionId") String definitionId,
                                        @Param("limit") int limit);

    @Select("SELECT id, tenant_id AS tenantId, definition_id AS definitionId,"
            + " dataset_id AS datasetId, name, version_a AS versionA, version_b AS versionB,"
            + " run_a AS runA, run_b AS runB, status, summary_json AS summaryJson,"
            + " created_by AS createdBy, created_at AS createdAt,"
            + " finished_at AS finishedAt"
            + " FROM ab_experiment WHERE status = 'RUNNING' ORDER BY created_at LIMIT #{limit}")
    List<ExperimentRow> runningExperiments(@Param("limit") int limit);

    @Update("UPDATE ab_experiment SET status = #{status}, summary_json = #{summaryJson},"
            + " finished_at = NOW(3) WHERE id = #{id}")
    int finishExperiment(@Param("id") String id, @Param("status") String status,
                         @Param("summaryJson") String summaryJson);

    record DatasetRow(String id, String tenantId, String name, String description,
                      String createdBy, Timestamp createdAt) {
    }

    record CaseRow(String id, String datasetId, String tenantId, String name, String inputJson,
                   String expectedJson, String assertionJson, boolean enabled,
                   Timestamp createdAt) {
    }

    record RunRow(String id, String datasetId, String tenantId, String definitionId,
                  String versionId, String status, int total, int passed, int failed,
                  String experimentId, String variant, String errorMessage, String createdBy,
                  Timestamp startedAt, Timestamp finishedAt, Timestamp createdAt) {
    }

    record ResultRow(String id, String runId, String caseId, String caseName,
                     String executionId, String status, Boolean passed, String actualJson,
                     Integer latencyMs, Integer tokenUsed, String errorMessage) {
    }

    record RunStats(long total, long passed, long failed, Double avgLatency, Double avgTokens) {
    }

    record ExperimentRow(String id, String tenantId, String definitionId, String datasetId,
                         String name, String versionA, String versionB, String runA, String runB,
                         String status, String summaryJson, String createdBy,
                         Timestamp createdAt, Timestamp finishedAt) {
    }
}
