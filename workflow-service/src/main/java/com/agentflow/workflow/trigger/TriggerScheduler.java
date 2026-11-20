package com.agentflow.workflow.trigger;

import com.agentflow.common.error.BusinessException;
import com.agentflow.workflow.lease.RedisLease;
import java.time.Duration;
import java.time.Instant;
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
 * <p>到点判断以「档位游标」为基准（{@code last_slot_at}；为空时退回 {@code last_fired_at}）：
 * 正常运行时每轮最多只有一档到期，停机恢复时按触发器的 {@link MisfirePolicy} 处理错过的档位
 * （窗口 24 小时，见 {@link TriggerService#dueSlots}）——只补最近一档、补齐最近若干档、
 * 或者整批跳过。三种策略都不做「无限重放」：每分钟任务停机 3 小时不会瞬间炸出 180 条执行。
 *
 * <p>补跑还要服从工作流的并发上限（{@code workflow_definition.max_concurrent}）：一批档位
 * 逐个检查余量，跑满就停下，游标停在最后一条真的跑过的档位上，下一轮接着补——
 * 宁可晚几分钟补完，也不要在同一秒把引擎和额度打满。
 */
@Component
public class TriggerScheduler {

    private static final Logger log = LoggerFactory.getLogger(TriggerScheduler.class);
    private static final String LEASE_KEY = "af:sched:leader";
    /** 租约 TTL 覆盖两轮扫描：这一轮异常退出时，下一轮仍有机会接管。 */
    private static final Duration LEASE_TTL = Duration.ofSeconds(60);

    private final TriggerService service;
    private final RedisLease lease;
    private final boolean enabled;

    public TriggerScheduler(TriggerService service, StringRedisTemplate redis,
                            @Value("${agentflow.trigger.enabled:true}") boolean enabled) {
        this.service = service;
        this.lease = new RedisLease(redis);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${agentflow.trigger.poll-interval-ms:30000}",
            initialDelayString = "${agentflow.trigger.initial-delay-ms:20000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        if (!lease.hold(LEASE_KEY, LEASE_TTL)) {
            log.debug("schedule lease held by another replica; skip this round");
            return;
        }
        Instant now = Instant.now();
        for (WorkflowTriggerMapper.Row row : service.enabledSchedules()) {
            try {
                var plan = service.dueSlots(row, now);
                if (plan.isEmpty()) {
                    continue;
                }
                if (!service.claimFire(row.id(), now)) {
                    log.debug("schedule trigger {} already claimed this minute", row.id());
                    continue;
                }
                if (plan.slots().isEmpty()) {
                    // SKIP 策略：这一批档位整个放弃，只推游标
                    service.skipMissed(row, now, plan);
                    continue;
                }
                int fired = 0;
                for (Instant slot : plan.slots()) {
                    var capacity = service.capacity(row);
                    if (!capacity.available()) {
                        // 工作流已经跑满：剩下的档位留到下一轮，游标停在最后一条真的跑过的档位上
                        service.defer(row, plan, fired, capacity, now);
                        break;
                    }
                    try {
                        var result = service.fireSchedule(row, now, slot, plan.skipped());
                        fired++;
                        log.info("schedule trigger {} fired slot={} execution={} status={}"
                                        + " skipped={}", row.id(), slot, result.executionId(),
                                result.status(), plan.skipped());
                    } catch (BusinessException e) {
                        // 预检查与插入之间被别的入口抢先占满：同样按「推迟」处理，不丢档
                        if (!TriggerService.isConcurrencyLimit(e)) {
                            throw e;
                        }
                        service.defer(row, plan, fired, service.capacity(row), now);
                        break;
                    }
                }
            } catch (Exception e) {
                // 一个触发器失败不该拖垮整轮扫描，也不该吞掉同一触发器后面还没跑的档位：
                // 已派发的档位都已记账，下一轮从这里继续（未派发的档位幂等键还没用过）
                log.warn("schedule trigger {} failed: {}", row.id(), e.getMessage());
                log.debug("schedule trigger failed", e);
            }
        }
    }
}
