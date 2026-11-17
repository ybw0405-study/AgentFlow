package com.agentflow.workflow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** RocketMQ 审计发布（生产路径）。发送失败不阻断业务（P0 已知限制，P1 出 outbox 兜底）。 */
@Component
@ConditionalOnProperty(name = "agentflow.rocketmq.enabled", havingValue = "true")
public class RocketMqAuditPublisher implements AuditPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RocketMqAuditPublisher.class);

    private final DefaultMQProducer producer;
    private final String topic;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RocketMqAuditPublisher(
            @Value("${agentflow.rocketmq.namesrv}") String namesrv,
            @Value("${agentflow.rocketmq.producer-group}") String group,
            @Value("${agentflow.rocketmq.topic}") String topic) throws Exception {
        this.topic = topic;
        this.producer = new DefaultMQProducer(group);
        this.producer.setNamesrvAddr(namesrv);
        this.producer.setSendMsgTimeout(3_000);
        this.producer.start();
    }

    @Override
    public void publish(AuditMessage msg) {
        try {
            Message message = new Message(topic, objectMapper.writeValueAsBytes(msg));
            message.setKeys(msg.id());
            message.putUserProperty("traceId", msg.traceId());
            producer.send(message);
        } catch (Exception e) {
            // P0 降级：记日志不阻断（README 已知限制；P1 引入 outbox 表 + relay）
            log.error("audit publish failed eventType={} id={}", msg.eventType(), msg.id(), e);
        }
    }

    @Override
    public void close() {
        producer.shutdown();
    }
}
