package com.agentflow.workflow.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * relay 语义：成功即收敛、失败退避、连续失败降级直写、彻底失败迁入隔离区。
 *
 * <p>「不放弃投递」不等于「无限重投」：DEAD 行保留原始载荷，管理端可列出、可重放。
 */
class AuditOutboxRelayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEAD_AFTER = 24;

    private static AuditOutboxMapper.OutboxRow row(String id, int attempts) throws Exception {
        AuditMessage msg = AuditMessage.of("t-1", "e-1", "u-1", "WF_EXEC_TRIGGERED",
                Map.of("k", "v"));
        return new AuditOutboxMapper.OutboxRow(id, "t-1", "WF_EXEC_TRIGGERED",
                JSON.writeValueAsString(msg), attempts, new Timestamp(System.currentTimeMillis()));
    }

    @Test
    void marksSentWhenSinkAccepts() throws Exception {
        AuditOutboxMapper mapper = mock(AuditOutboxMapper.class);
        AuditDelivery delivery = mock(AuditDelivery.class);
        when(mapper.due(anyInt())).thenReturn(List.of(row("a", 0)));

        new AuditOutboxRelay(mapper, delivery, 3, DEAD_AFTER).relay();

        verify(delivery).deliver(any(AuditMessage.class));
        verify(mapper).markSent("a");
        verify(mapper, never()).markRetry(anyString(), anyInt(), anyString(), anyInt());
    }

    @Test
    void backsOffAndKeepsPendingWhenSinkFails() throws Exception {
        AuditOutboxMapper mapper = mock(AuditOutboxMapper.class);
        AuditDelivery delivery = mock(AuditDelivery.class);
        when(mapper.due(anyInt())).thenReturn(List.of(row("b", 0)));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(delivery).deliver(any(AuditMessage.class));

        new AuditOutboxRelay(mapper, delivery, 3, DEAD_AFTER).relay();

        verify(mapper, never()).markSent(anyString());
        verify(mapper, never()).markDead(anyString(), anyString(), anyString());
        // 首次失败：attempts=1，退避 5s
        verify(mapper).markRetry(eq("b"), eq(1), anyString(), eq(5));
    }

    @Test
    void degradesToDirectSinkAfterThreshold() throws Exception {
        AuditOutboxMapper mapper = mock(AuditOutboxMapper.class);
        AuditDelivery delivery = mock(AuditDelivery.class);
        DirectAuditSink direct = mock(DirectAuditSink.class);
        when(mapper.due(anyInt())).thenReturn(List.of(row("c", 3)));
        when(delivery.degraded()).thenReturn(direct);

        new AuditOutboxRelay(mapper, delivery, 3, DEAD_AFTER).relay();

        verify(direct).deliver(any(AuditMessage.class));
        verify(delivery, never()).deliver(any(AuditMessage.class));
        verify(mapper).markSent("c");
    }

    @Test
    void scanFailureDoesNotThrow() throws Exception {
        AuditOutboxMapper mapper = mock(AuditOutboxMapper.class);
        AuditDelivery delivery = mock(AuditDelivery.class);
        when(mapper.due(anyInt())).thenThrow(new IllegalStateException("db down"));

        // 调度器不能被一次扫描失败打断
        new AuditOutboxRelay(mapper, delivery, 3, DEAD_AFTER).relay();
        verify(delivery, never()).deliver(any(AuditMessage.class));
        verify(mapper, times(0)).markSent(anyString());
    }

    @Test
    void unreadablePayloadIsIsolatedWithOriginalPayload() throws Exception {
        AuditOutboxMapper mapper = mock(AuditOutboxMapper.class);
        AuditDelivery delivery = mock(AuditDelivery.class);
        DirectAuditSink direct = mock(DirectAuditSink.class);
        when(mapper.due(anyInt())).thenReturn(List.of(
                new AuditOutboxMapper.OutboxRow("d", "t-1", "WF_PUBLISHED", "{not json", 0,
                        Timestamp.from(Instant.now()))));
        when(delivery.degraded()).thenReturn(direct);

        new AuditOutboxRelay(mapper, delivery, 3, DEAD_AFTER).relay();

        // 旧实现写一条骨架就 markSent：完整载荷永久丢失却记为已送达。
        // 现在只隔离，原始 message_json 留在库里等人工重放。
        verify(direct, never()).deliver(any(AuditMessage.class));
        verify(mapper, never()).markSent(anyString());
        verify(mapper).markDead(eq("d"), eq("payload-unreadable"), anyString());
    }

    @Test
    void isolatesAfterDeadThresholdInsteadOfRetryingForever() throws Exception {
        AuditOutboxMapper mapper = mock(AuditOutboxMapper.class);
        AuditDelivery delivery = mock(AuditDelivery.class);
        DirectAuditSink direct = mock(DirectAuditSink.class);
        when(mapper.due(anyInt())).thenReturn(List.of(row("c", DEAD_AFTER - 1)));
        when(delivery.degraded()).thenReturn(direct);
        org.mockito.Mockito.doThrow(new IllegalStateException("db read-only"))
                .when(direct).deliver(any(AuditMessage.class));

        new AuditOutboxRelay(mapper, delivery, 3, DEAD_AFTER).relay();

        verify(mapper).markDead(eq("c"), eq("delivery-exhausted"), anyString());
        verify(mapper, never()).markRetry(anyString(), anyInt(), anyString(), anyInt());
        verify(mapper, never()).markSent(anyString());
    }

    @Test
    void keepsRetryingBelowDeadThreshold() throws Exception {
        AuditOutboxMapper mapper = mock(AuditOutboxMapper.class);
        AuditDelivery delivery = mock(AuditDelivery.class);
        DirectAuditSink direct = mock(DirectAuditSink.class);
        when(mapper.due(anyInt())).thenReturn(List.of(row("c", DEAD_AFTER - 2)));
        when(delivery.degraded()).thenReturn(direct);
        org.mockito.Mockito.doThrow(new IllegalStateException("db read-only"))
                .when(direct).deliver(any(AuditMessage.class));

        new AuditOutboxRelay(mapper, delivery, 3, DEAD_AFTER).relay();

        verify(mapper, never()).markDead(anyString(), anyString(), anyString());
        verify(mapper).markRetry(eq("c"), eq(DEAD_AFTER - 1), anyString(), eq(60));
    }
}
