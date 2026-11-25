package com.agentflow.workflow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计 outbox relay：把已落地的事件按退避投递出去。
 *
 * <p>这里**不删除、不放弃**任何事件。连续失败先退避重投，达到
 * {@code degrade-after-attempts}（默认 3）后改走直写审计表；若连直写也一直失败，
 * 达到 {@code dead-after-attempts}（默认 24）就迁入 DEAD 隔离区——原始
 * {@code message_json} 完整保留，管理端可列出、可重放。这样「不丢」不再等价于
 * 「每 60 秒重试到永远」，也不会出现「载荷坏了写条骨架就记为已送达」的静默丢失。
 *
 * <p>DEAD 是人工介入的入口，不是终态放弃：运维修好库或解析问题后调
 * {@code POST /api/v1/admin/audit-outbox/dead/{id}/replay} 即可重新投递。
 */
@Component
public class AuditOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxRelay.class);
    private static final int BATCH = 200;

    private final AuditOutboxMapper outbox;
    private final OutboxClaimer claimer;
    private final AuditDelivery delivery;
    /** 领取租约（秒）：崩溃残留的 PROCESSING 超过此时长后由下一轮回收。 */
    private static final int CLAIM_LEASE_SECONDS = 120;
    private final int degradeAfter;
    private final int deadAfter;
    private final ObjectMapper json = new ObjectMapper();
    /** 已送达 outbox 的保留天数：由配置注入，不再写死 7；单测默认 7。 */
    @Value("${agentflow.audit.purge-days:7}")
    private int purgeDays = 7;

    /** Spring 注入：claimer 是独立 Bean，其 @Transactional 经代理生效。
     * 多构造函数时必须显式标注，否则 Spring 退回找无参构造并启动失败。 */
    @Autowired
    public AuditOutboxRelay(AuditOutboxMapper outbox, OutboxClaimer claimer,
                            AuditDelivery delivery,
                            @Value("${agentflow.audit.degrade-after-attempts:3}")
                            int degradeAfter,
                            @Value("${agentflow.audit.dead-after-attempts:24}")
                            int deadAfter) {
        this.outbox = outbox;
        this.claimer = claimer;
        this.delivery = delivery;
        this.degradeAfter = Math.max(1, degradeAfter);
        // 至少给降级通道几次机会，不能刚降级就隔离
        this.deadAfter = Math.max(this.degradeAfter + 1, deadAfter);
    }

    /** 测试构造：claimer 直接包同一次 mapper，无 Spring 代理需求。 */
    AuditOutboxRelay(AuditOutboxMapper outbox, AuditDelivery delivery,
                     int degradeAfter, int deadAfter) {
        this(outbox, new OutboxClaimer(outbox), delivery, degradeAfter, deadAfter);
    }

    @Scheduled(fixedDelayString = "${agentflow.audit.relay-interval-ms:2000}",
            initialDelayString = "${agentflow.audit.relay-initial-delay-ms:4000}")
    public void relay() {
        List<AuditOutboxMapper.OutboxRow> rows;
        try {
            // 短事务领取：FOR UPDATE SKIP LOCKED + claim 成 PROCESSING 后提交，
            // 本批行由当前副本独占；网络投递在事务外完成，不拉长事务。
            rows = claimer.claim(BATCH);
            // 崩溃残留的 PROCESSING 超过租约后回收，避免永久卡在领取态。
            outbox.reclaimExpired(CLAIM_LEASE_SECONDS);
        } catch (RuntimeException e) {
            // 库暂时不可用：下一轮再来，不抛（避免调度器停摆）
            log.warn("audit outbox scan failed: {}", e.toString());
            return;
        }
        int sent = 0;
        int retried = 0;
        int dead = 0;
        for (AuditOutboxMapper.OutboxRow row : rows) {
            AuditMessage msg;
            try {
                msg = json.readValue(row.messageJson(), AuditMessage.class);
            } catch (Exception e) {
                // 载荷坏了重投也没用，但绝不能写一条骨架就 markSent：那样完整载荷
                // 会被永久跳过、界面还显示已送达。隔离并保留原文，等人工修好再重放。
                log.error("audit outbox payload unreadable; isolated id={} type={}",
                        row.id(), row.eventType(), e);
                outbox.markDead(row.id(), "payload-unreadable", truncate(e.toString()));
                dead++;
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
                String error = truncate(e.toString());
                if (attempts >= deadAfter) {
                    // 降级直写与 outbox 同库，库长期不可写时这行会一直 60s 重试到
                    // 永远。到这里改为隔离：不再刷重试，但载荷与失败原因都留着。
                    outbox.markDead(row.id(), "delivery-exhausted", error);
                    log.error("audit outbox isolated after {} attempts id={} type={} ({})",
                            attempts, row.id(), row.eventType(), error);
                    dead++;
                    continue;
                }
                int delay = Math.min(5 * attempts, 60);
                outbox.markRetry(row.id(), attempts, error, delay);
                retried++;
                if (attempts == degradeAfter || attempts % 20 == 0) {
                    log.warn("audit delivery failing id={} type={} attempts={} ({})",
                            row.id(), row.eventType(), attempts, error);
                }
            }
        }
        if ((sent > 0 || retried > 0 || dead > 0) && log.isDebugEnabled()) {
            log.debug("audit relay sent={} retried={} isolated={} sink={}", sent, retried,
                    dead, delivery.primary().name());
        }
        if (rows.size() >= BATCH) {
            log.warn("audit outbox backlog: scanned a full batch ({}), pending={}", BATCH,
                    safePending());
        }
    }

    /**
     * 水位告警：没有 Micrometer 依赖，就定期把队列状态写进日志。
     *
     * <p>DEAD 非空或最老 PENDING 超过 10 分钟时升到 WARN——只靠人主动打开管理端
     * 水位页，等于没有告警。
     */
    @Scheduled(fixedDelayString = "${agentflow.audit.report-interval-ms:300000}",
            initialDelayString = "${agentflow.audit.report-initial-delay-ms:60000}")
    public void report() {
        try {
            long pending = outbox.pendingCount();
            long retrying = outbox.retryingCount();
            long dead = outbox.deadCount();
            Timestamp oldest = outbox.oldestPendingAt();
            long age = oldest == null ? 0
                    : Math.max(0, Instant.now().getEpochSecond()
                            - oldest.toInstant().getEpochSecond());
            String summary = "audit outbox pending={} retrying={} dead={} "
                    + "oldestPendingAgeSeconds={} sink={}";
            if (dead > 0 || age > 600) {
                log.warn(summary, pending, retrying, dead, age, delivery.primary().name());
            } else {
                log.info(summary, pending, retrying, dead, age, delivery.primary().name());
            }
        } catch (RuntimeException e) {
            log.warn("audit outbox report failed: {}", e.toString());
        }
    }

    /** 已投递行定期清理：outbox 只是投递队列，审计本体在 audit_event。DEAD 行不清理。 */
    @Scheduled(cron = "${agentflow.audit.purge-cron:0 30 4 * * *}")
    public void purge() {
        int days = Math.max(1, purgeDays);
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
