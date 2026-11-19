package com.agentflow.workflow.gate;

import com.agentflow.common.trace.TraceIdContext;
import com.agentflow.workflow.mq.RocketMqProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 裁决广播（af-gate-decision）：给引擎一条**独立的**入站通道。
 *
 * <p>为什么 HTTP 之外还要 MQ：HTTP 是「控制面推到引擎」，引擎被网络策略隔离、控制面正在
 * 重启、relay 还在退避时都推不到；MQ 是「引擎自己来拉」，消息留在 broker 里，引擎一回来
 * 就能续跑。两条通道都幂等（引擎侧重复裁决返回 ALREADY_RESOLVED），所以可以并行存在。
 *
 * <p>发送时机放在 HTTP 尝试**之后**：正常情况下 HTTP 先落地，MQ 消息到达时只会计一次
 * duplicate；引擎不可达时这条消息才是真正的补偿路径。
 *
 * <p>失败只告警不抛：裁决已经写进 outbox（用户动作不丢），MQ 只是多一条更快的路。
 */
@Component
public class GateDecisionPublisher {

    private static final Logger log = LoggerFactory.getLogger(GateDecisionPublisher.class);

    private final RocketMqProducer producer;
    private final String topic;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper();

    public GateDecisionPublisher(
            RocketMqProducer producer,
            @Value("${agentflow.rocketmq.enabled:false}") boolean enabled,
            @Value("${agentflow.rocketmq.gate-topic:af-gate-decision}") String topic) {
        this.producer = producer;
        this.enabled = enabled;
        this.topic = topic;
    }

    public void publish(String executionId, String nodeKey, String decision, String resolvedBy,
                        String comment) {
        if (!enabled) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", executionId);
        payload.put("nodeKey", nodeKey);
        payload.put("decision", decision);
        payload.put("resolvedBy", resolvedBy);
        payload.put("comment", comment);
        payload.put("occurredAt", java.time.Instant.now().toString());
        try {
            producer.send(topic, executionId + ":" + nodeKey,
                    json.writeValueAsBytes(payload),
                    Map.of("traceId", TraceIdContext.currentOrCreate()));
        } catch (Exception e) {
            log.warn("gate decision not published to mq execution={} node={} ({})",
                    executionId, nodeKey, e.toString());
        }
    }
}
