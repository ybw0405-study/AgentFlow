package com.agentflow.workflow.trigger;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时触发的扫描器：每 30 秒看一次有没有到点的 cron。
 *
 * <p>分钟级精度（Spring CronExpression 最小粒度也是分钟），到点判断以 last_fired_at 为基准，
 * 所以重启后不会把当天已经跑过的任务再补一遍；触发本身用「触发器 + 分钟」做幂等键，
 * 多实例同时扫描也只会产生一次执行。
 */
@Component
public class TriggerScheduler {

    private static final Logger log = LoggerFactory.getLogger(TriggerScheduler.class);

    private final TriggerService service;
    private final boolean enabled;

    public TriggerScheduler(TriggerService service,
                            @Value("${agentflow.trigger.enabled:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${agentflow.trigger.poll-interval-ms:30000}",
            initialDelayString = "${agentflow.trigger.initial-delay-ms:20000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        for (WorkflowTriggerMapper.Row row : service.enabledSchedules()) {
            try {
                if (!service.isDue(row, now)) {
                    continue;
                }
                var result = service.fireSchedule(row, now);
                log.info("schedule trigger {} fired execution={} status={}",
                        row.id(), result.executionId(), result.status());
            } catch (Exception e) {
                // 一个触发器失败不该拖垮整轮扫描：记录后继续下一个
                log.warn("schedule trigger {} failed: {}", row.id(), e.getMessage());
                log.debug("schedule trigger failed", e);
            }
        }
    }
}
