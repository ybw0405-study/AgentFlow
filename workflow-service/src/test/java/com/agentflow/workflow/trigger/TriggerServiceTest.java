package com.agentflow.workflow.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.execution.ExecutionService;
import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 定时触发的多副本语义：分钟键稳定、claim 独占、Redis 故障时 fail-open。 */
class TriggerServiceTest {

    private WorkflowTriggerMapper triggers;
    private ExecutionService executions;
    private ValueOperations<String, String> ops;
    private TriggerService service;

    @BeforeEach
    void setUp() {
        triggers = Mockito.mock(WorkflowTriggerMapper.class);
        executions = Mockito.mock(ExecutionService.class);
        var definitions = Mockito.mock(WorkflowDefinitionMapper.class);
        var audit = Mockito.mock(AuditPublisher.class);
        var redis = Mockito.mock(StringRedisTemplate.class);
        ops = Mockito.mock(ValueOperations.class);
        Mockito.doReturn(ops).when(redis).opsForValue();
        service = new TriggerService(triggers, definitions, executions, audit, redis);
    }

    @Test
    void minuteKeyIsTruncatedToTheMinute() {
        Instant first = Instant.parse("2026-11-20T01:00:07Z");
        Instant second = Instant.parse("2026-11-20T01:00:59Z");

        assertEquals("2026-11-20T09:00", TriggerService.minuteOf("Asia/Shanghai", first));
        assertEquals(TriggerService.minuteOf("Asia/Shanghai", first),
                TriggerService.minuteOf("Asia/Shanghai", second));
    }

    @Test
    void minuteKeyFollowsTheTriggerTimezone() {
        Instant instant = Instant.parse("2026-11-20T01:00:07Z");

        assertEquals("2026-11-20T01:00", TriggerService.minuteOf("UTC", instant));
        assertEquals("2026-11-19T20:00", TriggerService.minuteOf("America/New_York", instant));
    }

    @Test
    void twoScansInTheSameMinuteUseOneIdempotencyKey() {
        when(executions.triggerFromExternal(anyString(), anyString(), anyString(), isNull(),
                anyString(), any(), anyString()))
                .thenReturn(new ExecutionService.TriggerResult("e-1", "PENDING", false));
        var row = schedule("tr-1");

        service.fireSchedule(row, Instant.parse("2026-11-20T01:00:07Z"));
        service.fireSchedule(row, Instant.parse("2026-11-20T01:00:41Z"));

        verify(executions, Mockito.times(2)).triggerFromExternal(eq("t-1"), eq("system:schedule"),
                eq("d-1"), isNull(), eq("sched-tr-1-2026-11-20T09:00"), any(), eq("sched-tr-1"));
        verify(triggers, Mockito.times(2)).markFired(eq("tr-1"), any());
    }

    @Test
    void claimWinsOnlyOnce() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true)
                .thenReturn(false);
        Instant now = Instant.parse("2026-11-20T01:00:07Z");

        assertTrue(service.claimFire("tr-1", now));
        assertFalse(service.claimFire("tr-1", now));
    }

    @Test
    void claimFailsOpenWhenRedisIsDown() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        assertTrue(service.claimFire("tr-1", Instant.now()));
    }

    private static WorkflowTriggerMapper.Row schedule(String id) {
        return new WorkflowTriggerMapper.Row(id, "d-1", "t-1", "SCHEDULE", "tok", "sec",
                "0 9 * * *", "Asia/Shanghai", null, null, true, null, "u-1", null, null);
    }

    @Test
    void scheduledRowKeepsItsVariables() {
        // 触发器上配的固定入参要随每次触发一起下发，不能被空 payload 覆盖
        var row = new WorkflowTriggerMapper.Row("tr-1", "d-1", "t-1", "SCHEDULE", "tok", "sec",
                "0 9 * * *", "Asia/Shanghai", "{\"question\":\"日报\"}", null, true, null,
                "u-1", null, null);
        when(executions.triggerFromExternal(anyString(), anyString(), anyString(), isNull(),
                anyString(), any(), anyString()))
                .thenReturn(new ExecutionService.TriggerResult("e-1", "PENDING", false));

        service.fireSchedule(row, Instant.parse("2026-11-20T01:00:07Z"));

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(executions).triggerFromExternal(anyString(), anyString(), anyString(), isNull(),
                anyString(), captor.capture(), anyString());
        assertEquals("日报", captor.getValue().get("question"));
    }
}
