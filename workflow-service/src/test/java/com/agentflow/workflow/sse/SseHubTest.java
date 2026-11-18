package com.agentflow.workflow.sse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 扇出：消息投递、畸形输入静默丢弃、失效订阅者剔除。
 *
 * <p>回归护栏：容器曾用手工构造的 MessageListenerAdapter 注册，其反射 invoker
 * 从未初始化，导致每条事件都抛 NPE、实时推送整体失效。此处直接驱动 dispatch，
 * 锁定「消息一定能到达已注册的 emitter」这一行为。
 */
class SseHubTest {

    private static String payload(String executionId) {
        return "{\"executionId\":\"" + executionId + "\",\"eventType\":\"NodeCompleted\"}";
    }

    @Test
    void deliversEventToSubscriber() throws Exception {
        SseHub hub = new SseHub();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register("exec-1", emitter);

        hub.dispatch(payload("exec-1"));

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void ignoresExecutionWithoutSubscriber() throws Exception {
        SseHub hub = new SseHub();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register("exec-1", emitter);

        assertDoesNotThrow(() -> hub.dispatch(payload("exec-other")));

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void dropsMalformedPayloadWithoutThrowing() {
        SseHub hub = new SseHub();

        assertDoesNotThrow(() -> {
            hub.dispatch("not-json");
        });
        assertDoesNotThrow(() -> {
            hub.dispatch("{}");
        });
    }

    @Test
    void evictsSubscriberThatFailsToSend() throws Exception {
        SseHub hub = new SseHub();
        SseEmitter emitter = mock(SseEmitter.class);
        // send 返回 void，桩必须用 doThrow 形式
        doThrow(new IOException("client gone"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        hub.register("exec-1", emitter);

        hub.dispatch(payload("exec-1"));
        hub.dispatch(payload("exec-1"));

        // 首次投递失败即从订阅表移除，后续不再尝试
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
