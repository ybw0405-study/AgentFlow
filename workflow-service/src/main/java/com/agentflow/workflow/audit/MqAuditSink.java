package com.agentflow.workflow.audit;

import com.agentflow.workflow.mq.RocketMqProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MQ 出口（af-audit-event）：审计由 audit-service 消费落库。
 *
 * <p>producer 的生命周期与重连由 {@link RocketMqProducer} 统一处理；这里只负责把
 * AuditMessage 序列化并带上消息键（key = 事件 id，便于按事件追溯与去重）。
 */
@Component
public class MqAuditSink implements AuditSink {

    private final RocketMqProducer producer;
    private final String topic;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MqAuditSink(RocketMqProducer producer,
                       @Value("${agentflow.rocketmq.topic:af-audit-event}") String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    @Override
    public String name() {
        return "mq:" + topic;
    }

    @Override
    public void deliver(AuditMessage msg) throws Exception {
        producer.send(topic, msg.id(), objectMapper.writeValueAsBytes(msg),
                Map.of("traceId", msg.traceId() == null ? "" : msg.traceId()));
    }
}
