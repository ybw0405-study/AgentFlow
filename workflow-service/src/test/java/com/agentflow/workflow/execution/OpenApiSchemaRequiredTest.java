package com.agentflow.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * CT-2 回归：Java record 上的 {@code @Schema(requiredMode = REQUIRED)} 必须真的被
 * swagger-core/springdoc 解析成 schema 的 required 数组，而不是「加了注解但没生效」。
 * 用 ModelConverters 直接解析，不需要起 Spring 全栈。
 */
class OpenApiSchemaRequiredTest {

    @Test
    void triggerDataAnnotatedComponentsBecomeRequired() {
        var schemas = ModelConverters.getInstance().readAll(ExecutionController.TriggerData.class);
        Schema<?> schema = schemas.get("TriggerData");
        assertNotNull(schema, "swagger-core 未解析出 TriggerData schema");
        List<String> required = schema.getRequired();
        assertNotNull(required, "TriggerData 缺少 required 数组：@Schema 未生效");
        assertTrue(required.containsAll(List.of(
                        "executionId", "status", "idempotentReplay", "eventsUrl", "streamUrl")),
                "TriggerData required 不含全部字段，实际为 " + required);
    }

    @Test
    void triggerRequestAnnotatedComponentsBecomeRequired() {
        var schemas = ModelConverters.getInstance().readAll(ExecutionController.TriggerRequest.class);
        Schema<?> schema = schemas.get("TriggerRequest");
        assertNotNull(schema);
        List<String> required = schema.getRequired();
        assertNotNull(required, "TriggerRequest 缺少 required 数组：@Schema 未生效");
        assertTrue(required.containsAll(List.of("versionId", "idempotencyKey")),
                "TriggerRequest required 不含 versionId/idempotencyKey，实际为 " + required);
    }
}
