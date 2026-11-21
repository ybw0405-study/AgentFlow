package com.agentflow.workflow.projector;

import com.agentflow.workflow.engine.EngineGatewayClient;
import com.agentflow.workflow.lease.RedisLease;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 事件投影器（D2）：轮询引擎全局事件尾读接口，把事件流投影到 MySQL 执行索引表。
 * 游标存 projector_cursor，与投影写入同事务；崩溃后从游标续读，事件幂等重放。
 *
 * <p><b>单实例约束（CON-2）</b>：budget_ledger.accrue 是 {@code calls_used = calls_used + 1}
 * 的累加写入，若两个副本从同一游标读到同一批事件并各自投影，月度账本会重复计费。
 * 因此与 ExecutionReaper / TriggerScheduler 同款，先用 {@link RedisLease} 抢「同一时刻只有
 * 一个副本投影」的尽力租约（Redis 不可用时 fail-open）。租约是减少重复劳动的优化，不是
 * 正确性依赖——部署上必须保证投影器单实例运行，或由下游唯一约束兜底。
 */
@Component
public class EventProjector {

    private static final Logger log = LoggerFactory.getLogger(EventProjector.class);
    private static final String LEASE_KEY = "af:projector:leader";
    /** 租约覆盖多轮轮询：本轮异常退出时下一轮仍有机会接管。 */
    private static final Duration LEASE_TTL = Duration.ofSeconds(60);

    private final EngineGatewayClient engine;
    private final ProjectorWriter writer;
    private final RedisLease lease;
    private final int pageSize;

    public EventProjector(EngineGatewayClient engine, ProjectorWriter writer,
                          StringRedisTemplate redis,
                          @Value("${agentflow.engine.poll-interval-ms:500}") int pollIntervalMs) {
        this.engine = engine;
        this.writer = writer;
        this.lease = new RedisLease(redis);
        this.pageSize = 500;
    }

    @Scheduled(fixedDelayString = "${agentflow.engine.poll-interval-ms:500}",
            initialDelay = 3000)
    public void poll() {
        if (!lease.hold(LEASE_KEY, LEASE_TTL)) {
            log.debug("projector lease held by another replica; skip this round");
            return;
        }
        try {
            long cursor = writer.currentCursor();
            while (true) {
                EngineGatewayClient.EventsPage page;
                try {
                    page = engine.tailEvents(cursor, pageSize);
                } catch (Exception e) {
                    log.debug("engine tail unavailable: {}", e.getMessage());
                    return;  // 下一轮再试；游标不动
                }
                if (page == null || page.events() == null || page.events().isEmpty()) {
                    return;
                }
                long next = writer.writeBatch(page.events());
                if (next <= cursor) {
                    // 游标未前进（重复页/写入幂等命中）：立即退出，避免死循环空转
                    log.warn("projector cursor did not advance at seq={}, stop this round", cursor);
                    return;
                }
                cursor = next;
                if (page.events().size() < pageSize) {
                    return;
                }
            }
        } catch (Exception e) {
            log.error("projector poll failed", e);
        }
    }
}
