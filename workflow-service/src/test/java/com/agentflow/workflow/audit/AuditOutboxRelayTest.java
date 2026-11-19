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
 * relay 语义：成功即收敛、失败退避、连续失败降级直写。
 *
 * <p>这里刻意断言「失败后仍然 PENDING」——审计不允许被放弃，attempts 只是退避依据。
 */
class AuditOutboxRelayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

        new AuditOutboxRelay(mapper, delivery, 3).relay();

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

        new AuditOutboxRelay(mapper, delivery, 3).relay();

        verify(mapper, never()).markSent(anyString());
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

        new AuditOutboxRelay(mapper, delivery, 3).relay();

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
        new AuditOutboxRelay(mapper, delivery, 3).relay();
        verify(delivery, never()).deliver(any(AuditMessage.class));
        verify(mapper, times(0)).markSent(anyString());
    }

    @Test
    void unreadablePayloadIsDeliveredAsSkeletonAndClosed() throws Exception {
        AuditOutboxMapper mapper = mock(AuditOutboxMapper.class);
        AuditDelivery delivery = mock(AuditDelivery.class);
        DirectAuditSink direct = mock(DirectAuditSink.class);
        when(mapper.due(anyInt())).thenReturn(List.of(
                new AuditOutboxMapper.OutboxRow("d", "t-1", "WF_PUBLISHED", "{not json", 0,
                        Timestamp.from(Instant.now()))));
        when(delivery.degraded()).thenReturn(direct);

        new AuditOutboxRelay(mapper, delivery, 3).relay();

        // 载荷坏了也不能无限重投：降级直写一条「事件类型 + 租户」可辨的审计
        verify(direct).deliver(any(AuditMessage.class));
        verify(mapper).markSent("d");
    }
}
