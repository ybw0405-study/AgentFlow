package com.agentflow.audit.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.audit.repo.AuditEventMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AuditEventServiceTest {

    private final AuditEventMapper mapper = Mockito.mock(AuditEventMapper.class);
    private final StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
    private AuditEventService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        service = new AuditEventService(mapper, redis, 168);
    }

    private AuditMessageDto msg(String id) {
        return new AuditMessageDto(id, "t1", "trace-1", null, "u1",
                "EXECUTION_TRIGGERED", Map.of("k", "v"), null, null, "2026-11-16T00:00:00Z");
    }

    @Test
    void firstDeliveryInserts() {
        when(ops.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        assertTrue(service.ingest(msg("e1")));
        verify(mapper).insert(any());
    }

    @Test
    void duplicateDeliverySkipped() {
        // 写库已确认的 DONE 标记才算重复；此时不应再打库
        when(ops.get("af:audit:seen:e1")).thenReturn("DONE");
        assertFalse(service.ingest(msg("e1")));
        verify(mapper, never()).insert(any());
    }

    @Test
    void pendingRemainderFromCrashStillInserts() {
        // 崩溃残留的 PENDING 不能当重复丢弃（旧实现在此永久丢事件）；
        // 必须尝试写库，由 DB 主键兜底去重。
        when(ops.get("af:audit:seen:e1")).thenReturn("PENDING");
        assertTrue(service.ingest(msg("e1")));
        verify(mapper).insert(any());
    }

    @Test
    void successfulInsertMarksDone() {
        when(ops.get("af:audit:seen:e1")).thenReturn(null);
        assertTrue(service.ingest(msg("e1")));
        verify(ops).set(eq("af:audit:seen:e1"), eq("DONE"), any(Duration.class));
    }

    @Test
    void duplicateKeyFallbackIsIdempotent() {
        when(ops.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("dup"));
        assertFalse(service.ingest(msg("e1")));
    }

    @Test
    void insertFailureClearsDedupForRetry() {
        when(ops.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(mapper.insert(any())).thenThrow(new RuntimeException("db down"));
        assertThrows(IllegalStateException.class, () -> service.ingest(msg("e1")));
        verify(redis).delete("af:audit:seen:e1");
    }
}
