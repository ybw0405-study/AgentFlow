package com.agentflow.workflow.trigger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 扫描循环的三层防线：租约只省重复劳动，claim 与幂等键才是「只跑一次」。 */
class TriggerSchedulerTest {

    private TriggerService service;
    private ValueOperations<String, String> ops;
    private TriggerScheduler scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = Mockito.mock(TriggerService.class);
        var redis = Mockito.mock(StringRedisTemplate.class);
        ops = Mockito.mock(ValueOperations.class);
        Mockito.doReturn(ops).when(redis).opsForValue();
        when(ops.get("af:sched:leader")).thenReturn(null);
        when(ops.setIfAbsent(eq("af:sched:leader"), anyString(), any(Duration.class)))
                .thenReturn(true);
        scheduler = new TriggerScheduler(service, redis, true);
    }

    @Test
    void firesDueTrigger() {
        var row = schedule("tr-1");
        when(service.enabledSchedules()).thenReturn(List.of(row));
        when(service.isDue(eq(row), any())).thenReturn(true);
        when(service.claimFire(eq("tr-1"), any())).thenReturn(true);
        when(service.fireSchedule(eq(row), any())).thenReturn(
                new TriggerService.FireResult("e-1", "PENDING", false));

        scheduler.poll();

        verify(service).fireSchedule(eq(row), any());
    }

    @Test
    void skipsTriggerThatAnotherReplicaAlreadyClaimed() {
        var row = schedule("tr-1");
        when(service.enabledSchedules()).thenReturn(List.of(row));
        when(service.isDue(eq(row), any())).thenReturn(true);
        when(service.claimFire(eq("tr-1"), any())).thenReturn(false);

        scheduler.poll();

        verify(service, never()).fireSchedule(any(), any());
    }

    @Test
    void skipsWholeRoundWhenAnotherReplicaHoldsTheLease() {
        when(ops.get("af:sched:leader")).thenReturn("other-replica");
        when(ops.setIfAbsent(eq("af:sched:leader"), anyString(), any(Duration.class)))
                .thenReturn(false);

        scheduler.poll();

        verify(service, never()).enabledSchedules();
    }

    @Test
    void keepsScanningOnEveryRoundWhenItHoldsTheLease() {
        when(service.enabledSchedules()).thenReturn(List.of());

        scheduler.poll();
        scheduler.poll();

        // 同一个进程连续两轮都要能扫表：租约到期被别的副本抢走之前，自己不能把自己挡在外面
        verify(service, Mockito.times(2)).enabledSchedules();
    }

    @Test
    void oneFailingTriggerDoesNotStopTheRound() {
        var first = schedule("tr-1");
        var second = schedule("tr-2");
        when(service.enabledSchedules()).thenReturn(List.of(first, second));
        when(service.isDue(eq(first), any())).thenThrow(new IllegalStateException("boom"));
        when(service.isDue(eq(second), any())).thenReturn(true);
        when(service.claimFire(eq("tr-2"), any())).thenReturn(true);
        when(service.fireSchedule(eq(second), any())).thenReturn(
                new TriggerService.FireResult("e-2", "PENDING", false));

        scheduler.poll();

        verify(service).fireSchedule(eq(second), any());
    }

    @Test
    void scansAnywayWhenRedisIsDown() {
        when(ops.get("af:sched:leader")).thenThrow(new IllegalStateException("redis down"));
        when(service.enabledSchedules()).thenReturn(List.of());

        scheduler.poll();

        verify(service).enabledSchedules();
    }

    @Test
    void doesNothingWhenDisabled() {
        var redis = Mockito.mock(StringRedisTemplate.class);
        var off = new TriggerScheduler(service, redis, false);

        off.poll();

        verify(service, never()).enabledSchedules();
    }

    private static WorkflowTriggerMapper.Row schedule(String id) {
        return new WorkflowTriggerMapper.Row(id, "d-" + id, "t-1", "SCHEDULE", "tok", "sec",
                "0 9 * * *", "Asia/Shanghai", null, null, true, null, "u-1", null, null);
    }
}
