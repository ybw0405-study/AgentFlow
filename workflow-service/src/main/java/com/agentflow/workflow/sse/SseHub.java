package com.agentflow.workflow.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 扇出：订阅 Redis 频道 af:exec-events，把事件推给本实例上的订阅者。
 * 断线重连由客户端带 afterSeq 追平，本组件只做 at-most-once 实时推送。
 *
 * <p>直接实现 {@link MessageListener}：容器注册即生效，不依赖
 * {@code MessageListenerAdapter} 的生命周期回调（后者在手工 new 时不会执行
 * {@code afterPropertiesSet}，导致反射 invoker 为空、每条消息都抛 NPE）。
 */
@Component
public class SseHub implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    public static final String CHANNEL = "af:exec-events";

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(Message message, byte[] pattern) {
        dispatch(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    /** 消息分发（独立出来便于单测直接投喂消息体）。 */
    void dispatch(String message) {
        String executionId;
        try {
            JsonNode node = objectMapper.readTree(message);
            executionId = node.path("executionId").asText(null);
        } catch (Exception e) {
            log.warn("dropping malformed event payload on {}: {}", CHANNEL, e.toString());
            return;
        }
        if (executionId == null) {
            return;
        }
        List<SseEmitter> list = emitters.get(executionId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("engine-event").data(message));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }
    }

    public SseEmitter register(String executionId, SseEmitter emitter) {
        emitters.computeIfAbsent(executionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(executionId, emitter));
        emitter.onTimeout(() -> remove(executionId, emitter));
        emitter.onError(e -> remove(executionId, emitter));
        return emitter;
    }

    private void remove(String executionId, SseEmitter emitter) {
        var list = emitters.get(executionId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(executionId);
            }
        }
    }

    /** 心跳：15s 一次注释帧，防代理断连。 */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        emitters.forEach((id, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ka"));
                } catch (IOException | IllegalStateException e) {
                    list.remove(emitter);
                }
            }
        });
    }
}
