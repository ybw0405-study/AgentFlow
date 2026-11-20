package com.agentflow.workflow.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.execution.ExecutionService;
import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 定时触发的语义：档位稳定、补偿有界、claim 独占、Redis 故障时 fail-open。 */
class TriggerServiceTest {

    private WorkflowTriggerMapper triggers;
    private WorkflowDefinitionMapper definitions;
    private ExecutionService executions;
    private ValueOperations<String, String> ops;
    private TriggerService service;

    @BeforeEach
    void setUp() {
        triggers = Mockito.mock(WorkflowTriggerMapper.class);
        executions = Mockito.mock(ExecutionService.class);
        definitions = Mockito.mock(WorkflowDefinitionMapper.class);
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
    void twoScansInTheSameSlotUseOneIdempotencyKey() {
        when(executions.triggerFromExternal(anyString(), anyString(), anyString(), isNull(),
                anyString(), any(), anyString()))
                .thenReturn(new ExecutionService.TriggerResult("e-1", "PENDING", false));
        var row = schedule("tr-1", "0 9 * * *", null);
        Instant slot = Instant.parse("2026-11-20T01:00:00Z");

        service.fireSchedule(row, Instant.parse("2026-11-20T01:00:07Z"), slot);
        service.fireSchedule(row, Instant.parse("2026-11-20T01:00:41Z"), slot);

        // 键来自档位而不是扫描时刻：两个副本在不同秒扫到，也指向同一条执行
        verify(executions, Mockito.times(2)).triggerFromExternal(eq("t-1"), eq("system:schedule"),
                eq("d-1"), isNull(), eq("sched-tr-1-2026-11-20T09:00"), any(), eq("sched-tr-1"));
    }

    @Test
    void firingRecordsBothTheWallClockAndTheSlot() {
        when(executions.triggerFromExternal(anyString(), anyString(), anyString(), isNull(),
                anyString(), any(), anyString()))
                .thenReturn(new ExecutionService.TriggerResult("e-1", "PENDING", false));
        var row = schedule("tr-1", "0 9 * * *", null);
        Instant slot = Instant.parse("2026-11-20T01:00:00Z");

        service.fireSchedule(row, Instant.parse("2026-11-20T01:37:00Z"), slot);

        // 真正跑的时间与代表的档位分开记：两者之差就是补偿延迟
        verify(triggers).markFired(eq("tr-1"), eq(Timestamp.from(Instant.parse(
                "2026-11-20T01:37:00Z"))), eq(Timestamp.from(slot)));
    }

    @Test
    void dueSlotIsNullBeforeTheNextOccurrence() {
        var row = schedule("tr-1", "0 9 * * *",
                Timestamp.from(Instant.parse("2026-11-20T01:00:00Z")));

        assertNull(service.dueSlot(row, Instant.parse("2026-11-20T05:00:00Z")));
    }

    @Test
    void dueSlotFiresTheCurrentMinuteForAFreshTrigger() {
        var row = schedule("tr-1", "* * * * *", null);

        // 新建的触发器以「一分钟前」为起点，所以整分扫描时只可能命中当前这一档
        assertEquals(Instant.parse("2026-11-20T01:00:00Z"),
                service.dueSlot(row, Instant.parse("2026-11-20T01:00:20Z")));
        assertNull(service.dueSlot(schedule("tr-2", "0 10 * * *", null),
                Instant.parse("2026-11-20T01:00:20Z")));
    }

    @Test
    void missedWindowsCatchUpWithTheMostRecentSlotOnly() {
        // 每分钟一档，停机 3 分钟：补的是最近一档，而不是把 3 档都重放
        var row = schedule("tr-1", "* * * * *",
                Timestamp.from(Instant.parse("2026-11-20T00:57:00Z")));

        assertEquals(Instant.parse("2026-11-20T01:00:00Z"),
                service.dueSlot(row, Instant.parse("2026-11-20T01:00:20Z")));
    }

    @Test
    void catchUpWindowIsBoundedToTheLastDay() {
        // 停机一周的日报：只回看 24 小时，补跑今天这一档，不追一周前的
        var row = schedule("tr-1", "0 9 * * *",
                Timestamp.from(Instant.parse("2026-11-13T01:00:00Z")));

        assertEquals(Instant.parse("2026-11-20T01:00:00Z"),
                service.dueSlot(row, Instant.parse("2026-11-20T01:05:00Z")));
    }

    @Test
    void slotStopsAtTheCurrentMinuteEvenForAFrequentCron() {
        // 每 15 分钟一档、停机 40 分钟：落在窗口内，取最近一档（01:00），不是 00:15
        var row = schedule("tr-1", "*/15 * * * *",
                Timestamp.from(Instant.parse("2026-11-20T00:20:00Z")));

        assertEquals(Instant.parse("2026-11-20T01:00:00Z"),
                service.dueSlot(row, Instant.parse("2026-11-20T01:01:00Z")));
    }

    @Test
    void nextSlotsAreRenderedInTheTriggerTimezone() {
        List<String> times = TriggerService.nextSlots("0 9 * * *", "Asia/Shanghai",
                Instant.parse("2026-11-20T01:30:00Z"), 2);

        assertEquals(List.of("2026-11-21 09:00", "2026-11-22 09:00"), times);
        assertEquals(List.of("2026-11-20 09:00", "2026-11-21 09:00"),
                TriggerService.nextSlots("0 9 * * *", "UTC",
                        Instant.parse("2026-11-20T01:30:00Z"), 2));
    }

    @Test
    void previewRejectsSubMinuteCron() {
        when(definitions.findByIdAndTenant("d-1", "t-1")).thenReturn(definition());

        var tenant = new TenantInfo("t-1", "u-1", "EDITOR");
        var error = assertThrows(BusinessException.class,
                () -> service.previewCron(tenant, "d-1", "*/5 * * * * *", null, 3));

        // 秒级 cron 在分钟粒度的调度器上会被静默吞掉一半，宁可拒绝
        assertTrue(error.getMessage().contains("granularity")
                || error.getMessage().contains("seconds"), error.getMessage());
    }

    @Test
    void previewNormalizesFiveFieldCronAndReturnsTheNextSlots() {
        when(definitions.findByIdAndTenant("d-1", "t-1")).thenReturn(definition());

        var preview = service.previewCron(new TenantInfo("t-1", "u-1", "EDITOR"),
                "d-1", "0 9 * * *", "Asia/Shanghai", 3);

        // 5 段归一成秒位为 0 的 6 段，前端回填的是这个规范形式
        assertEquals("0 0 9 * * *", preview.cron());
        assertEquals(3, preview.times().size());
        assertTrue(preview.times().get(0).matches("\\d{4}-\\d{2}-\\d{2} 09:00"),
                preview.times().get(0));
    }

    @Test
    void previewClampsTheRequestedCount() {
        when(definitions.findByIdAndTenant("d-1", "t-1")).thenReturn(definition());

        var preview = service.previewCron(new TenantInfo("t-1", "u-1", "EDITOR"),
                "d-1", "0 9 * * *", null, 200);

        assertEquals(20, preview.times().size());
        assertEquals("Asia/Shanghai", preview.timezone());
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

    @Test
    void scheduledRowKeepsItsVariables() {
        // 触发器上配的固定入参要随每次触发一起下发，不能被空 payload 覆盖
        var row = new WorkflowTriggerMapper.Row("tr-1", "d-1", "t-1", "SCHEDULE", "tok", "sec",
                "0 9 * * *", "Asia/Shanghai", "{\"question\":\"日报\"}", null, true, null, null,
                "u-1", null, null);
        when(executions.triggerFromExternal(anyString(), anyString(), anyString(), isNull(),
                anyString(), any(), anyString()))
                .thenReturn(new ExecutionService.TriggerResult("e-1", "PENDING", false));

        service.fireSchedule(row, Instant.parse("2026-11-20T01:00:07Z"),
                Instant.parse("2026-11-20T01:00:00Z"));

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(executions).triggerFromExternal(anyString(), anyString(), anyString(), isNull(),
                anyString(), captor.capture(), anyString());
        assertEquals("日报", captor.getValue().get("question"));
    }

    private static WorkflowTriggerMapper.Row schedule(String id, String cron,
                                                      Timestamp lastFiredAt) {
        return new WorkflowTriggerMapper.Row(id, "d-1", "t-1", "SCHEDULE", "tok", "sec",
                cron, "Asia/Shanghai", null, null, true, lastFiredAt, null, "u-1", null, null);
    }

    private static WorkflowDefinitionMapper.DefinitionRow definition() {
        return new WorkflowDefinitionMapper.DefinitionRow(1L, "d-1", "t-1", "日报", null,
                "PUBLISHED", "v-1", null, null, "u-1", null, null);
    }
}
