package com.agentflow.workflow.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MQ 关闭（默认本地开发）：审计只记日志。 */
@Component
@ConditionalOnProperty(name = "agentflow.rocketmq.enabled", havingValue = "false",
        matchIfMissing = true)
public class NoopAuditPublisher implements AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopAuditPublisher.class);

    @Override
    public void publish(AuditMessage msg) {
        log.debug("audit(noop) {} tenant={} exec={}", msg.eventType(), msg.tenantId(),
                msg.executionId());
    }
}
