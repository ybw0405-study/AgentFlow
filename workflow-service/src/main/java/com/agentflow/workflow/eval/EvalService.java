package com.agentflow.workflow.eval;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.common.trace.TraceIdContext;
import com.agentflow.workflow.audit.AuditMessage;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.workflow.eval.EvalMapper.CaseRow;
import com.agentflow.workflow.eval.EvalMapper.ExperimentRow;
import com.agentflow.workflow.eval.EvalMapper.ResultRow;
import com.agentflow.workflow.eval.EvalMapper.RunRow;
import com.agentflow.workflow.eval.EvalMapper.RunStats;
import com.agentflow.workflow.execution.ExecutionService;
import com.agentflow.workflow.execution.WorkflowExecutionMapper;
import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import com.agentflow.workflow.workflow.WorkflowVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 评测集与 A/B 对比。
 *
 * <p>执行模型：一次评测 = 对数据集里每个启用用例触发一次真实执行（幂等键
 * {@code eval:{runId}:{caseId}}），然后由轮询器在用例执行落终态后取 result 做断言。
 * 不引入新的执行通道：评测跑的就是线上同一套引擎与版本快照，结果才有可比性。
 *
 * <p>A/B = 同一数据集上跑两个版本（同一 DS L之外的唯一变量是 versionId），
 * 汇总通过率 / 平均耗时 / 平均 token 得出结论。在线流量切分仍由 canary 负责，
 * 两者不混用：canary 决定「谁被路由」，A/B 决定「哪个版本更好」。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT");
    private static final int MAX_CASES_PER_RUN = 50;
    private static final int POLL_RUNS = 5;

    private final EvalMapper evals;
    private final WorkflowDefinitionMapper definitions;
    private final WorkflowVersionMapper versions;
    private final WorkflowExecutionMapper executions;
    private final ExecutionService executionService;
    private final EngineGatewayClient engine;
    private final AuditPublisher audit;
    private final ObjectMapper json = new ObjectMapper();

    public EvalService(EvalMapper evals, WorkflowDefinitionMapper definitions,
                       WorkflowVersionMapper versions, WorkflowExecutionMapper executions,
                       ExecutionService executionService, EngineGatewayClient engine,
                       AuditPublisher audit) {
        this.evals = evals;
        this.definitions = definitions;
        this.versions = versions;
        this.executions = executions;
        this.executionService = executionService;
        this.engine = engine;
        this.audit = audit;
    }

    // ---------- 数据集与用例 ----------

    public Map<String, Object> createDataset(TenantInfo tenant, String name, String description) {
        requireEditor(tenant);
        String id = UUID.randomUUID().toString();
        evals.insertDataset(new EvalMapper.DatasetRow(id, tenant.tenantId(), name, description,
                tenant.userId(), null));
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "EVAL_DATASET_CREATED", Map.of("datasetId", id, "name", name)));
        return detail(tenant, id);
    }

    public List<EvalMapper.DatasetRow> listDatasets(TenantInfo tenant) {
        return evals.listDatasets(tenant.tenantId());
    }

    public Map<String, Object> detail(TenantInfo tenant, String datasetId) {
        var dataset = requireDataset(tenant, datasetId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("dataset", dataset);
        view.put("cases", evals.listCases(datasetId, tenant.tenantId()));
        view.put("runs", evals.listRuns(datasetId, tenant.tenantId(), 10));
        return view;
    }

    public void deleteDataset(TenantInfo tenant, String datasetId) {
        if (!tenant.atLeast("ADMIN")) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "admin required");
        }
        requireDataset(tenant, datasetId);
        evals.deleteDataset(datasetId, tenant.tenantId());
    }

    public Map<String, Object> addCase(TenantInfo tenant, String datasetId, String name,
                                       Map<String, Object> input, Object expected,
                                       Map<String, Object> assertion) {
        requireEditor(tenant);
        requireDataset(tenant, datasetId);
        if (assertion == null || assertion.get("mode") == null) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001,
                    "assertion.mode is required");
        }
        String id = UUID.randomUUID().toString();
        evals.insertCase(new CaseRow(id, datasetId, tenant.tenantId(), name,
                writeJson(input == null ? Map.of() : input), expected == null ? null
                : writeJson(expected), writeJson(assertion), true, null));
        return detail(tenant, datasetId);
    }

    public void deleteCase(TenantInfo tenant, String caseId) {
        requireEditor(tenant);
        if (evals.deleteCase(caseId, tenant.tenantId()) == 0) {
            throw notFound();
        }
    }

    // ---------- 运行 ----------

    public Map<String, Object> createRun(TenantInfo tenant, String datasetId, String definitionId,
                                         String versionId) {
        requireEditor(tenant);
        String runId = startRun(tenant, datasetId, definitionId, versionId, null, null);
        return runDetail(tenant, runId);
    }

    private String startRun(TenantInfo tenant, String datasetId, String definitionId,
                            String versionId, String experimentId, String variant) {
        requireDataset(tenant, datasetId);
        var cases = evals.listCases(datasetId, tenant.tenantId()).stream()
                .filter(CaseRow::enabled).toList();
        if (cases.isEmpty()) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001,
                    "dataset has no enabled cases");
        }
        if (cases.size() > MAX_CASES_PER_RUN) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001,
                    "dataset exceeds " + MAX_CASES_PER_RUN + " cases");
        }
        var version = resolveVersion(tenant, definitionId, versionId);
        String runId = UUID.randomUUID().toString();
        evals.insertRun(new RunRow(runId, datasetId, tenant.tenantId(), definitionId,
                version.id(), "RUNNING", cases.size(), 0, 0, experimentId, variant, null,
                tenant.userId(), null, null, null));

        int dispatchFailures = 0;
        for (CaseRow evalCase : cases) {
            String executionId = null;
            String status = "PENDING";
            String error = null;
            try {
                var triggered = executionService.trigger(tenant, definitionId, version.id(),
                        "eval:" + runId + ":" + evalCase.id(), readMap(evalCase.inputJson()),
                        null, TraceIdContext.currentOrCreate());
                executionId = triggered.executionId();
            } catch (BusinessException e) {
                status = "ERROR";
                error = truncate(e.getMessage());
                dispatchFailures++;
            }
            evals.insertResult(new ResultRow(UUID.randomUUID().toString(), runId, evalCase.id(),
                    evalCase.name(), executionId, status, null, null, null, null, error));
        }
        if (dispatchFailures == cases.size()) {
            evals.updateRunStats(runId, "FAILED", cases.size(), 0, cases.size(),
                    "all cases failed to dispatch");
        }
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "EVAL_RUN_STARTED", Map.of("runId", runId, "datasetId", datasetId,
                        "definitionId", definitionId, "versionId", version.id(),
                        "cases", cases.size(), "experimentId",
                        experimentId == null ? "" : experimentId)));
        return runId;
    }

    public Map<String, Object> runDetail(TenantInfo tenant, String runId) {
        var run = evals.findRun(runId, tenant.tenantId());
        if (run == null) {
            throw notFound();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("run", run);
        view.put("results", viewResults(runId));
        return view;
    }

    public List<RunRow> listRuns(TenantInfo tenant, String datasetId, int limit) {
        requireDataset(tenant, datasetId);
        return evals.listRuns(datasetId, tenant.tenantId(), Math.min(Math.max(limit, 1), 100));
    }

    // ---------- A/B ----------

    public Map<String, Object> createExperiment(TenantInfo tenant, String definitionId,
                                                String datasetId, String name,
                                                String versionA, String versionB) {
        requireEditor(tenant);
        requireDataset(tenant, datasetId);
        if (versionA.equals(versionB)) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001,
                    "versionA and versionB must differ");
        }
        var a = resolveVersion(tenant, definitionId, versionA);
        var b = resolveVersion(tenant, definitionId, versionB);
        String id = UUID.randomUUID().toString();
        evals.insertExperiment(new ExperimentRow(id, tenant.tenantId(), definitionId, datasetId,
                name, a.id(), b.id(), null, null, "RUNNING", null, tenant.userId(), null, null));
        String runA = startRun(tenant, datasetId, definitionId, a.id(), id, "A");
        String runB = startRun(tenant, datasetId, definitionId, b.id(), id, "B");
        evals.bindRuns(id, runA, runB);
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "EVAL_EXPERIMENT_STARTED", Map.of("experimentId", id,
                        "definitionId", definitionId, "versionA", a.id(),
                        "versionB", b.id())));
        return experimentDetail(tenant, id);
    }

    public Map<String, Object> experimentDetail(TenantInfo tenant, String id) {
        var experiment = evals.findExperiment(id, tenant.tenantId());
        if (experiment == null) {
            throw notFound();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("experiment", experiment);
        view.put("runA", experiment.runA() == null ? null
                : evals.findRun(experiment.runA(), tenant.tenantId()));
        view.put("runB", experiment.runB() == null ? null
                : evals.findRun(experiment.runB(), tenant.tenantId()));
        view.put("statsA", experiment.runA() == null ? null : evals.stats(experiment.runA()));
        view.put("statsB", experiment.runB() == null ? null : evals.stats(experiment.runB()));
        return view;
    }

    public List<ExperimentRow> listExperiments(TenantInfo tenant, String definitionId,
                                               int limit) {
        return evals.listExperiments(tenant.tenantId(), definitionId,
                Math.min(Math.max(limit, 1), 100));
    }

    // ---------- 轮询收口 ----------

    @Scheduled(fixedDelayString = "${agentflow.eval.poll-interval-ms:3000}", initialDelay = 6000)
    public void poll() {
        for (RunRow run : evals.runningRuns(POLL_RUNS)) {
            try {
                advance(run);
            } catch (Exception e) {
                log.warn("eval run {} advance failed: {}", run.id(), e.getMessage());
            }
        }
        finishExperiments();
    }

    private void advance(RunRow run) {
        for (ResultRow result : evals.openResults(run.id())) {
            var execution = executions.findForEval(result.executionId());
            if (execution == null) {
                continue; // 投影尚未追上，下一轮再试
            }
            if (!TERMINAL.contains(execution.status())) {
                evals.updateResult(result.id(), "RUNNING", null, null, null, null, null);
                continue;
            }
            Integer latency = latencyOf(execution);
            if ("SUCCEEDED".equals(execution.status())) {
                Map<String, Object> state = engine.executionState(result.executionId());
                Object actual = state.get("result");
                var evalCase = evals.findCase(result.caseId(), run.tenantId());
                Map<String, Object> assertion = evalCase == null ? Map.of()
                        : readMap(evalCase.assertionJson());
                Object expected = evalCase == null ? null : readAny(evalCase.expectedJson());
                var verdict = EvalAssertions.evaluate(assertion, expected, actual);
                evals.updateResult(result.id(), verdict.passed() ? "PASSED" : "FAILED",
                        verdict.passed(), writeJson(actual), latency, execution.tokenUsed(),
                        verdict.passed() ? null : truncate(verdict.message()));
            } else {
                evals.updateResult(result.id(), "ERROR", false, null, latency,
                        execution.tokenUsed(),
                        execution.errorCode() == null ? execution.status()
                                : execution.errorCode());
            }
        }
        recount(run);
    }

    private void recount(RunRow run) {
        List<ResultRow> results = evals.listResults(run.id());
        int passed = (int) results.stream().filter(r -> Boolean.TRUE.equals(r.passed())).count();
        int failed = (int) results.stream()
                .filter(r -> "FAILED".equals(r.status()) || "ERROR".equals(r.status())).count();
        evals.updateRunCounters(run.id(), passed, failed);
        // 结果条数不足就先不收口：createRun 先写 run 行、再逐条写用例结果，
        // 轮询正好落在中间时会看到「0 条结果」，把 run 直接判成成功（total 也被改成 0），
        // 前端显示「0/0 通过」而实际用例还在跑。
        boolean allResultsVisible = results.size() >= run.total();
        boolean done = allResultsVisible && results.stream().noneMatch(
                r -> "PENDING".equals(r.status()) || "RUNNING".equals(r.status()));
        if (done) {
            evals.updateRunStats(run.id(), "SUCCEEDED", run.total(), passed, failed, null);
        }
    }

    private void finishExperiments() {
        for (ExperimentRow experiment : evals.runningExperiments(POLL_RUNS)) {
            if (experiment.runA() == null || experiment.runB() == null) {
                continue;
            }
            var runA = evals.findRun(experiment.runA(), experiment.tenantId());
            var runB = evals.findRun(experiment.runB(), experiment.tenantId());
            if (runA == null || runB == null
                    || "RUNNING".equals(runA.status()) || "RUNNING".equals(runB.status())) {
                continue;
            }
            RunStats a = evals.stats(runA.id());
            RunStats b = evals.stats(runB.id());
            Map<String, Object> summary = summarise(a, b);
            evals.finishExperiment(experiment.id(), "DONE", writeJson(summary));
            audit.publish(AuditMessage.of(experiment.tenantId(), null, experiment.createdBy(),
                    "EVAL_EXPERIMENT_DONE", Map.of("experimentId", experiment.id(),
                            "winner", String.valueOf(summary.get("winner")))));
        }
    }

    /** 通过率优先；差值在 ±0.5pp 内视为持平，再比平均耗时（低者胜），最后比 token。 */
    static Map<String, Object> summarise(RunStats a, RunStats b) {
        double rateA = a.total() == 0 ? 0 : (double) a.passed() / a.total();
        double rateB = b.total() == 0 ? 0 : (double) b.passed() / b.total();
        double delta = rateB - rateA;
        String winner;
        if (Math.abs(delta) >= 0.005) {
            winner = delta > 0 ? "B" : "A";
        } else {
            double latA = a.avgLatency() == null ? 0 : a.avgLatency();
            double latB = b.avgLatency() == null ? 0 : b.avgLatency();
            double tokA = a.avgTokens() == null ? 0 : a.avgTokens();
            double tokB = b.avgTokens() == null ? 0 : b.avgTokens();
            if (Math.abs(latB - latA) > 50) {
                winner = latB < latA ? "B" : "A";
            } else if (Math.abs(tokB - tokA) > 0.5) {
                winner = tokB < tokA ? "B" : "A";
            } else {
                winner = "TIE";
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("passRateA", round(rateA));
        summary.put("passRateB", round(rateB));
        summary.put("deltaPassRate", round(delta));
        summary.put("avgLatencyA", round(a.avgLatency()));
        summary.put("avgLatencyB", round(b.avgLatency()));
        summary.put("avgTokensA", round(a.avgTokens()));
        summary.put("avgTokensB", round(b.avgTokens()));
        summary.put("winner", winner);
        return summary;
    }

    // ---------- 辅助 ----------

    private EvalMapper.DatasetRow requireDataset(TenantInfo tenant, String datasetId) {
        var dataset = evals.findDataset(datasetId, tenant.tenantId());
        if (dataset == null) {
            throw notFound();
        }
        return dataset;
    }

    private WorkflowVersionMapper.VersionRow resolveVersion(TenantInfo tenant,
                                                            String definitionId,
                                                            String versionId) {
        var definition = definitions.findByIdAndTenant(definitionId, tenant.tenantId());
        if (definition == null) {
            throw notFound();
        }
        String target = versionId != null ? versionId : definition.currentVersionId();
        if (target == null) {
            throw new BusinessException(ErrorCode.WF_STATE_001, "no published version");
        }
        var version = versions.findById(target);
        if (version == null || !definitionId.equals(version.definitionId())) {
            throw notFound();
        }
        return version;
    }

    private static void requireEditor(TenantInfo tenant) {
        if (!tenant.atLeast("EDITOR")) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
    }

    private static Integer latencyOf(WorkflowExecutionMapper.EvalExecutionRow execution) {
        if (execution.startedAt() == null || execution.finishedAt() == null) {
            return null;
        }
        return (int) Math.max(0, execution.finishedAt().getTime()
                - execution.startedAt().getTime());
    }

    private static double round(Double value) {
        return value == null ? 0 : Math.round(value * 1000d) / 1000d;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Object readAny(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize eval payload", e);
        }
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
    }

    /** 供控制器回显：把结果里的 actual_json 解析成对象（前端直接渲染）。 */
    public List<Map<String, Object>> viewResults(String runId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ResultRow row : evals.listResults(runId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.id());
            view.put("caseId", row.caseId());
            view.put("caseName", row.caseName());
            view.put("executionId", row.executionId());
            view.put("status", row.status());
            view.put("passed", row.passed());
            view.put("actual", readAny(row.actualJson()));
            view.put("latencyMs", row.latencyMs());
            view.put("tokenUsed", row.tokenUsed());
            view.put("errorMessage", row.errorMessage());
            rows.add(view);
        }
        return rows;
    }
}
