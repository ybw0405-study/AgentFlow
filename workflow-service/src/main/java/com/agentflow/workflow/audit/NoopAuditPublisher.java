package com.agentflow.workflow.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MQ 关闭时的审计出口：只写进程日志，不落库。
 *
 * <p>这是显式的降级选择而非默认行为——本地演示不依赖任何中间件即可跑通，
 * 但代价是审计不持久。因此每次落一条 WARN（而非 debug），让「审计未入库」
 * 在日志与告警里始终可见；需要留痕的环境必须开启 {@code agentflow.rocketmq.enabled}，
 * 生产环境由 {@code ProductionGuard} 兜底要求 MQ 可用。
 */
@Component
@ConditionalOnProperty(name = "agentflow.rocketmq.enabled", havingValue = "false",
        matchIfMissing = true)
public class NoopAuditPublisher implements AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopAuditPublisher.class);

    @Override
    public void publish(AuditMessage msg) {
        log.warn("audit NOT persisted (mq disabled) eventType={} tenant={} exec={} actor={}",
                msg.eventType(), msg.tenantId(), msg.executionId(), msg.actorId());
    }
}
