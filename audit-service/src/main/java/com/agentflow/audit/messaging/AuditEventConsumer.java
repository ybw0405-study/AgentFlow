package com.agentflow.audit.messaging;

import com.agentflow.audit.service.AuditEventService;
import com.agentflow.audit.service.AuditMessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** RocketMQ 推送消费（af-audit-event）；任何失败返回 RECONSUME_LATER 让 broker 重投。 */
@Component
@ConditionalOnProperty(name = "agentflow.rocketmq.enabled", havingValue = "true")
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final DefaultMQPushConsumer consumer;
    private final AuditEventService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditEventConsumer(
            AuditEventService service,
            @Value("${agentflow.rocketmq.namesrv}") String namesrv,
            @Value("${agentflow.rocketmq.consumer-group}") String group,
            @Value("${agentflow.rocketmq.topic}") String topic) {
        this.service = service;
        this.consumer = new DefaultMQPushConsumer(group);
        this.consumer.setNamesrvAddr(namesrv);
        this.consumer.setConsumeThreadMin(2);
        this.consumer.setConsumeThreadMax(4);
        try {
            this.consumer.subscribe(topic, "*");
        } catch (Exception e) {
            throw new IllegalStateException("subscribe failed", e);
        }
    }

    @PostConstruct
    void start() throws Exception {
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (var msg : msgs) {
                try {
                    var dto = objectMapper.readValue(
                            new String(msg.getBody(), StandardCharsets.UTF_8),
                            AuditMessageDto.class);
                    service.ingest(dto);
                } catch (Exception e) {
                    log.error("audit consume failed, will retry", e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("audit consumer started topic={}", "af-audit-event");
    }

    @PreDestroy
    void stop() {
        consumer.shutdown();
    }
}
