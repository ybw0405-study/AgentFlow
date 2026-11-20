package com.agentflow.workflow.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时触发的扫描器：默认每 30 秒看一次有没有到点的 cron。
 *
 * <p>分钟级精度（Spring CronExpression 最小粒度也是分钟）。多副本下有三层防线，从外到内：
 *
 * <ol>
 *   <li><b>扫描租约</b>：同一时刻只让一个副本扫表，省掉 N 份重复 SELECT 与重复日志。
 *       这是「尽力而为」的优化，不是正确性依赖——租约丢失时下面两层仍然成立。</li>
 *   <li><b>分钟 claim</b>：每个触发器每分钟只有一个副本能拿到 Redis 键，其余直接跳过。</li>
 *   <li><b>幂等键</b>：{@code sched-{triggerId}-{分钟}}，最终由
 *       {@code workflow_execution} 的唯一约束兜底。即使前两层同时失效，同一分钟也只会有一条执行。</li>
 * </ol>
 *
 * <p>到点判断以 last_fired_at 为基准：正常运行时每轮最多只有一档到期，停机恢复时会补跑
 * 最近的一档（窗口 24 小时，见 {@link TriggerService#dueSlot}）。补跑的是「最近那一次」，
 * 不是把错过的每一档都重放一遍——停机 3 小时的每分钟任务不会瞬间炸出 180 条执行。
 */
@Component
public class TriggerScheduler {

    private static final Logger log = LoggerFactory.getLogger(TriggerScheduler.class);
    private static final String LEASE_KEY = "af:sched:leader";
    /** 租约 TTL 覆盖两轮扫描：这一轮异常退出时，下一轮仍有机会接管。 */
    private static final Duration LEASE_TTL = Duration.ofSeconds(60);

    private final TriggerService service;
    private final StringRedisTemplate redis;
    private final boolean enabled;
    /** 每个进程一个 id：续租时用它判断「这把锁是不是我自己持有的」。 */
    private final String instanceId = UUID.randomUUID().toString();

    public TriggerScheduler(TriggerService service, StringRedisTemplate redis,
                            @Value("${agentflow.trigger.enabled:true}") boolean enabled) {
        this.service = service;
        this.redis = redis;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${agentflow.trigger.poll-interval-ms:30000}",
            initialDelayString = "${agentflow.trigger.initial-delay-ms:20000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        if (!holdLease()) {
            log.debug("schedule lease held by another replica; skip this round");
            return;
        }
        Instant now = Instant.now();
        for (WorkflowTriggerMapper.Row row : service.enabledSchedules()) {
            try {
                Instant slot = service.dueSlot(row, now);
                if (slot == null) {
                    continue;
                }
                if (!service.claimFire(row.id(), now)) {
                    log.debug("schedule trigger {} already claimed this minute", row.id());
                    continue;
                }
                var result = service.fireSchedule(row, now, slot);
                log.info("schedule trigger {} fired slot={} execution={} status={}",
                        row.id(), slot, result.executionId(), result.status());
            } catch (Exception e) {
                // 一个触发器失败不该拖垮整轮扫描：记录后继续下一个
                log.warn("schedule trigger {} failed: {}", row.id(), e.getMessage());
                log.debug("schedule trigger failed", e);
            }
        }
    }

    /**
     * 拿/续扫描租约：自己持有就续期，否则抢一次。
     *
     * <p>GET 与 SETNX 之间有一个极小的窗口，抢输的一方可能也认为自己拿到了——所以它只是
     * 减少重复劳动的优化；真正的「只跑一次」由 claim 与幂等键保证。
     */
    private boolean holdLease() {
        try {
            String holder = redis.opsForValue().get(LEASE_KEY);
            if (instanceId.equals(holder)) {
                redis.expire(LEASE_KEY, LEASE_TTL);
                return true;
            }
            return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(LEASE_KEY, instanceId, LEASE_TTL));
        } catch (Exception e) {
            // Redis 不可用时宁可每个副本都扫：漏跑比重复扫描严重得多
            log.warn("schedule lease unavailable, scan anyway: {}", e.toString());
            return true;
        }
    }
}
