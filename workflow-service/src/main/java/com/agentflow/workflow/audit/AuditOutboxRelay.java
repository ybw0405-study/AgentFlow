package com.agentflow.workflow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计 outbox relay：把已落地的事件按退避投递出去。
 *
 * <p>与 gate outbox 的关键差异：这里**没有终态放弃**。审批超时后重投没有意义，
 * 审计不同——晚到十分钟也是审计，晚到十天也比没有强。因此 attempts 只用于退避
 * 与告警，行始终留在 PENDING，直到投递成功为止。
 *
 * <p>降级：同一行连续失败达到 {@code degrade-after-attempts}（默认 3）后，改走直写
 * 审计表；这样 MQ 长期不可用时，审计不会积压成「不可见的黑洞」。
 */
@Component
public class AuditOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxRelay.class);
    private static final int BATCH = 200;

    private final AuditOutboxMapper outbox;
    private final AuditDelivery delivery;
    private final int degradeAfter;
    private final ObjectMapper json = new ObjectMapper();

    public AuditOutboxRelay(AuditOutboxMapper outbox, AuditDelivery delivery,
                            @Value("${agentflow.audit.degrade-after-attempts:3}")
                            int degradeAfter) {
        this.outbox = outbox;
        this.delivery = delivery;
        this.degradeAfter = Math.max(1, degradeAfter);
    }

    @Scheduled(fixedDelayString = "${agentflow.audit.relay-interval-ms:2000}",
            initialDelayString = "${agentflow.audit.relay-initial-delay-ms:4000}")
    public void relay() {
        List<AuditOutboxMapper.OutboxRow> rows;
        try {
            rows = outbox.due(BATCH);
        } catch (RuntimeException e) {
            // 库暂时不可用：下一轮再来，不抛（避免调度器停摆）
            log.warn("audit outbox scan failed: {}", e.toString());
            return;
        }
        int sent = 0;
        int retried = 0;
        for (AuditOutboxMapper.OutboxRow row : rows) {
            AuditMessage msg;
            try {
                msg = json.readValue(row.messageJson(), AuditMessage.class);
            } catch (Exception e) {
                // 载荷坏了重投也没用：降级直写（能落一条是一条），并记为已处理
                log.error("audit outbox payload unreadable id={} type={}", row.id(),
                        row.eventType(), e);
                try {
                    delivery.degraded().deliver(new AuditMessage(row.id(), row.tenantId(),
                            "-", null, null, row.eventType(), null, null, null, null));
                } catch (Exception ignored) {
                    // 直写也失败：留给下一轮
                    continue;
                }
                outbox.markSent(row.id());
                continue;
            }
            try {
                if (row.attempts() >= degradeAfter) {
                    delivery.degraded().deliver(msg);
                    log.warn("audit degraded to direct sink after {} attempts id={} type={}",
                            row.attempts(), row.id(), row.eventType());
                } else {
                    delivery.deliver(msg);
                }
                outbox.markSent(row.id());
                sent++;
            } catch (Exception e) {
                int attempts = row.attempts() + 1;
                int delay = Math.min(5 * attempts, 60);
                outbox.markRetry(row.id(), attempts, truncate(e.toString()), delay);
                retried++;
                if (attempts == degradeAfter || attempts % 20 == 0) {
                    log.warn("audit delivery failing id={} type={} attempts={} ({})",
                            row.id(), row.eventType(), attempts, e.toString());
                }
            }
        }
        if ((sent > 0 || retried > 0) && log.isDebugEnabled()) {
            log.debug("audit relay sent={} retried={} sink={}", sent, retried,
                    delivery.primary().name());
        }
        if (rows.size() >= BATCH) {
            log.warn("audit outbox backlog: scanned a full batch ({}), pending={}", BATCH,
                    safePending());
        }
    }

    /** 已投递行定期清理：outbox 只是投递队列，审计本体在 audit_event。 */
    @Scheduled(cron = "${agentflow.audit.purge-cron:0 30 4 * * *}")
    public void purge() {
        int days = 7;
        int total = 0;
        while (true) {
            int removed = outbox.purgeSent(days, 5_000);
            total += removed;
            if (removed < 5_000) {
                break;
            }
        }
        if (total > 0) {
            log.info("audit outbox purged {} delivered rows older than {}d", total, days);
        }
    }

    private long safePending() {
        try {
            return outbox.pendingCount();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
