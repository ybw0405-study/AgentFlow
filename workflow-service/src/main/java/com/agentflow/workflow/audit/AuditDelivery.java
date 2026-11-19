package com.agentflow.workflow.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 出口选择：{@code agentflow.audit.sink} = auto | mq | direct。
 *
 * <ul>
 *   <li>mq：只走 RocketMQ（audit-service 消费落库，支持下游多方订阅）；</li>
 *   <li>direct：只直写 audit_event（无 MQ 环境的默认）；</li>
 *   <li>auto：MQ 开启则走 MQ，否则直写。</li>
 * </ul>
 *
 * <p>无论选哪个，relay 在连续失败后都会降级到 {@link #degraded()}（直写），
 * 保证审计最终一定落地。
 */
@Component
public class AuditDelivery {

    private static final Logger log = LoggerFactory.getLogger(AuditDelivery.class);

    private final AuditSink primary;
    private final DirectAuditSink direct;

    public AuditDelivery(@Value("${agentflow.audit.sink:auto}") String sink,
                         @Value("${agentflow.rocketmq.enabled:false}") boolean mqEnabled,
                         MqAuditSink mq, DirectAuditSink direct) {
        this.direct = direct;
        this.primary = switch (sink == null ? "auto" : sink.toLowerCase()) {
            case "mq" -> mq;
            case "direct" -> direct;
            default -> mqEnabled ? mq : direct;
        };
        log.info("audit sink = {} (configured={}, mqEnabled={})", primary.name(), sink, mqEnabled);
    }

    public void deliver(AuditMessage msg) throws Exception {
        primary.deliver(msg);
    }

    /** 降级通道：直写审计表，任何环境都可用。 */
    public DirectAuditSink degraded() {
        return direct;
    }

    public AuditSink primary() {
        return primary;
    }
}
