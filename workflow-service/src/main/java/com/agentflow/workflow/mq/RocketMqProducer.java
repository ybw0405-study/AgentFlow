package com.agentflow.workflow.mq;

import java.util.Map;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 共享 MQ 生产者：一个实例发多个 topic（审计、裁决…）。
 *
 * <p>懒启动 + 失败自愈：broker 没起来不应该连带服务起不来；发送失败就丢弃当前 producer，
 * 下一次重新建连，避免一直往已坏的连接上写。发送本身不在这里重试——重试策略在上层
 * （审计走 outbox relay，裁决走 gate outbox 与引擎侧幂等），这里只如实报告失败。
 */
@Component
public class RocketMqProducer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RocketMqProducer.class);

    private final String namesrv;
    private final String group;
    private volatile DefaultMQProducer producer;

    public RocketMqProducer(
            @Value("${agentflow.rocketmq.namesrv:127.0.0.1:9876}") String namesrv,
            @Value("${agentflow.rocketmq.producer-group:af-workflow-producer}") String group) {
        this.namesrv = namesrv;
        this.group = group;
    }

    public void send(String topic, String key, byte[] body,
                     Map<String, String> properties) throws Exception {
        DefaultMQProducer current = ensureProducer();
        Message message = new Message(topic, body);
        if (key != null) {
            message.setKeys(key);
        }
        if (properties != null) {
            properties.forEach((k, v) -> {
                if (v != null) {
                    message.putUserProperty(k, v);
                }
            });
        }
        try {
            current.send(message);
        } catch (Exception e) {
            shutdown();
            throw e;
        }
    }

    private DefaultMQProducer ensureProducer() throws Exception {
        DefaultMQProducer current = producer;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (producer == null) {
                DefaultMQProducer created = new DefaultMQProducer(group);
                created.setNamesrvAddr(namesrv);
                created.setSendMsgTimeout(3_000);
                created.start();
                producer = created;
                log.info("mq producer started namesrv={} group={}", namesrv, group);
            }
            return producer;
        }
    }

    private void shutdown() {
        synchronized (this) {
            if (producer != null) {
                try {
                    producer.shutdown();
                } catch (RuntimeException e) {
                    log.debug("mq producer shutdown failed: {}", e.toString());
                }
                producer = null;
            }
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
