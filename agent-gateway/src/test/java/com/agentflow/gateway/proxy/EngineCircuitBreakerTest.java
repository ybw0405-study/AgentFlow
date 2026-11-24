package com.agentflow.gateway.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 三态状态机：CLOSED 计数 → OPEN 冷却 → HALF_OPEN 探测 → 收敛。 */
class EngineCircuitBreakerTest {

    private final AtomicLong clock = new AtomicLong(0);

    private EngineCircuitBreaker breaker(int threshold, long cooldownMs) {
        return new EngineCircuitBreaker(threshold, cooldownMs, clock::get);
    }

    @Test
    void closedOpensAfterConsecutiveFailuresReachThreshold() {
        var cb = breaker(3, 10_000);

        for (int i = 0; i < 2; i++) {
            assertTrue(cb.allowRequest());
            cb.onFailure();
        }
        assertEquals(EngineCircuitBreaker.State.CLOSED, cb.state());
        assertEquals(2, cb.consecutiveFailures());

        assertTrue(cb.allowRequest());
        cb.onFailure();
        assertEquals(EngineCircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void openRejectsRequestsWhileCoolingDown() {
        var cb = breaker(1, 10_000);
        assertTrue(cb.allowRequest());
        cb.onFailure();
        assertEquals(EngineCircuitBreaker.State.OPEN, cb.state());

        clock.set(5_000);
        assertFalse(cb.allowRequest());
        assertFalse(cb.allowRequest());
        assertEquals(EngineCircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void openTransitionsToHalfOpenAfterCooldownAndAdmitsSingleProbe() {
        var cb = breaker(1, 10_000);
        assertTrue(cb.allowRequest());
        cb.onFailure();

        clock.set(10_000);
        assertTrue(cb.allowRequest());
        assertEquals(EngineCircuitBreaker.State.HALF_OPEN, cb.state());

        // 探测在途，其余请求拒绝
        assertFalse(cb.allowRequest());
        assertEquals(EngineCircuitBreaker.State.HALF_OPEN, cb.state());
    }

    @Test
    void halfOpenClosesOnProbeSuccess() {
        var cb = breaker(1, 10_000);
        assertTrue(cb.allowRequest());
        cb.onFailure();

        clock.set(10_000);
        assertTrue(cb.allowRequest());
        assertEquals(EngineCircuitBreaker.State.HALF_OPEN, cb.state());

        cb.onSuccess();
        assertEquals(EngineCircuitBreaker.State.CLOSED, cb.state());
        assertEquals(0, cb.consecutiveFailures());
    }

    @Test
    void halfOpenReopensOnProbeFailureAndStartsNewCooldown() {
        var cb = breaker(1, 10_000);
        assertTrue(cb.allowRequest());
        cb.onFailure();

        clock.set(10_000);
        assertTrue(cb.allowRequest());
        cb.onFailure();
        assertEquals(EngineCircuitBreaker.State.OPEN, cb.state());

        // 重新冷却：10_000 打开，需到 20_000 才 HALF_OPEN
        clock.set(15_000);
        assertFalse(cb.allowRequest());
        clock.set(20_000);
        assertTrue(cb.allowRequest());
        assertEquals(EngineCircuitBreaker.State.HALF_OPEN, cb.state());
    }

    @Test
    void successResetsConsecutiveFailuresInClosedState() {
        var cb = breaker(3, 10_000);
        assertTrue(cb.allowRequest());
        cb.onFailure();
        assertTrue(cb.allowRequest());
        cb.onFailure();
        assertEquals(2, cb.consecutiveFailures());

        assertTrue(cb.allowRequest());
        cb.onSuccess();
        assertEquals(0, cb.consecutiveFailures());
        assertEquals(EngineCircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        try {
            new EngineCircuitBreaker(0, 10_000);
            assertFalse(true, "should have thrown");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new EngineCircuitBreaker(5, 0);
            assertFalse(true, "should have thrown");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
