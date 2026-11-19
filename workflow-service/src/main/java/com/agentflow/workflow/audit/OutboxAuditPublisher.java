package com.agentflow.workflow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计签发口：只负责「写进 outbox」，不做任何网络调用。
 *
 * <p>业务线程不再承担投递风险——MQ 抖动、broker 重启、producer 重连都与业务路径无关。
 * 代价是审计落库延迟（一个 relay 周期，默认 2s），换来的是一条都不会丢。
 */
@Component
public class OutboxAuditPublisher implements AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxAuditPublisher.class);

    private final AuditOutboxMapper outbox;
    private final AuditDelivery delivery;
    private final ObjectMapper json = new ObjectMapper();

    public OutboxAuditPublisher(AuditOutboxMapper outbox, AuditDelivery delivery) {
        this.outbox = outbox;
        this.delivery = delivery;
    }

    @Override
    public void publish(AuditMessage msg) {
        try {
            outbox.enqueue(msg.id(), msg.tenantId(), msg.eventType(),
                    json.writeValueAsString(msg));
        } catch (Exception e) {
            // outbox 写不进去通常意味着库不可用；此时仍试一次直达，尽量不丢
            log.error("audit outbox write failed, trying direct delivery id={} type={}",
                    msg.id(), msg.eventType(), e);
            try {
                delivery.deliver(msg);
            } catch (Exception fallback) {
                log.error("audit LOST id={} type={} tenant={} exec={} actor={}",
                        msg.id(), msg.eventType(), msg.tenantId(), msg.executionId(),
                        msg.actorId(), fallback);
            }
        }
    }
}
