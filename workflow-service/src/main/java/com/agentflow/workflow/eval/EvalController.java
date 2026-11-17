package com.agentflow.workflow.eval;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.tenant.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 评测集 / 运行 / A-B 对比（P3）。 */
@RestController
public class EvalController {

    private final EvalService service;

    public EvalController(EvalService service) {
        this.service = service;
    }

    public record CreateDatasetRequest(@NotBlank @Size(min = 1, max = 128) String name,
                                       @Size(max = 512) String description) {
    }

    /** assertion 形如 {"mode":"EXACT"} / {"mode":"CONTAINS"} / {"mode":"NUMERIC","path":"score","min":0.8}。 */
    public record AddCaseRequest(@NotBlank @Size(min = 1, max = 160) String name,
                                 Map<String, Object> input, Object expected,
                                 Map<String, Object> assertion) {
    }

    public record StartRunRequest(@NotBlank String definitionId, String versionId) {
    }

    public record StartExperimentRequest(@NotBlank String definitionId,
                                         @NotBlank String datasetId,
                                         @NotBlank @Size(max = 160) String name,
                                         @NotBlank String versionA,
                                         @NotBlank String versionB) {
    }

    @GetMapping("/api/v1/evals/datasets")
    public ApiResponse<List<EvalMapper.DatasetRow>> datasets() {
        return ApiResponse.ok(service.listDatasets(TenantContext.require()));
    }

    @PostMapping("/api/v1/evals/datasets")
    public ApiResponse<Map<String, Object>> createDataset(
            @Validated @RequestBody CreateDatasetRequest body) {
        return ApiResponse.ok(service.createDataset(TenantContext.require(), body.name(),
                body.description()));
    }

    @GetMapping("/api/v1/evals/datasets/{id}")
    public ApiResponse<Map<String, Object>> dataset(@PathVariable String id) {
        return ApiResponse.ok(service.detail(TenantContext.require(), id));
    }

    @DeleteMapping("/api/v1/evals/datasets/{id}")
    public ApiResponse<Map<String, Object>> deleteDataset(@PathVariable String id) {
        service.deleteDataset(TenantContext.require(), id);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PostMapping("/api/v1/evals/datasets/{id}/cases")
    public ApiResponse<Map<String, Object>> addCase(@PathVariable String id,
                                                    @Validated @RequestBody AddCaseRequest body) {
        return ApiResponse.ok(service.addCase(TenantContext.require(), id, body.name(),
                body.input(), body.expected(), body.assertion()));
    }

    @DeleteMapping("/api/v1/evals/cases/{id}")
    public ApiResponse<Map<String, Object>> deleteCase(@PathVariable String id) {
        service.deleteCase(TenantContext.require(), id);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PostMapping("/api/v1/evals/datasets/{id}/runs")
    public ApiResponse<Map<String, Object>> startRun(@PathVariable String id,
                                                     @Validated @RequestBody
                                                     StartRunRequest body) {
        return ApiResponse.ok(service.createRun(TenantContext.require(), id, body.definitionId(),
                body.versionId()));
    }

    @GetMapping("/api/v1/evals/datasets/{id}/runs")
    public ApiResponse<List<EvalMapper.RunRow>> runs(@PathVariable String id,
                                                     @RequestParam(defaultValue = "20")
                                                     int limit) {
        return ApiResponse.ok(service.listRuns(TenantContext.require(), id, limit));
    }

    @GetMapping("/api/v1/evals/runs/{id}")
    public ApiResponse<Map<String, Object>> run(@PathVariable String id) {
        return ApiResponse.ok(service.runDetail(TenantContext.require(), id));
    }

    @PostMapping("/api/v1/evals/experiments")
    public ApiResponse<Map<String, Object>> startExperiment(
            @Validated @RequestBody StartExperimentRequest body) {
        return ApiResponse.ok(service.createExperiment(TenantContext.require(),
                body.definitionId(), body.datasetId(), body.name(), body.versionA(),
                body.versionB()));
    }

    @GetMapping("/api/v1/evals/experiments")
    public ApiResponse<List<EvalMapper.ExperimentRow>> experiments(
            @RequestParam(required = false) String definitionId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.listExperiments(TenantContext.require(), definitionId,
                limit));
    }

    @GetMapping("/api/v1/evals/experiments/{id}")
    public ApiResponse<Map<String, Object>> experiment(@PathVariable String id) {
        return ApiResponse.ok(service.experimentDetail(TenantContext.require(), id));
    }
}
