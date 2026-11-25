package com.agentflow.gateway.proxy;

import java.util.function.LongSupplier;

/**
 * /engine 上游熔断器：CLOSED / OPEN / HALF_OPEN 三态状态机（对齐 Resilience4j 的简化语义）。
 *
 * <ul>
 * <li>CLOSED：正常放行，连续失败达到 {@code failureThreshold} 后转 OPEN；</li>
 * <li>OPEN：快速失败（不再调用上游），冷却 {@code cooldownMillis} 后转 HALF_OPEN；</li>
 * <li>HALF_OPEN：只放行一个探测请求，成功转 CLOSED、失败转 OPEN。</li>
 * </ul>
 *
 * <p>线程安全：状态切换频率低，用 synchronized 保证「状态判定 + 计数更新」原子，
 * 避免两个并发请求同时越过失败阈值、或两个探测请求同时进入 HALF_OPEN。
 */
public final class EngineCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long cooldownMillis;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtMillis = 0L;
    private boolean halfOpenProbeInFlight = false;

    public EngineCircuitBreaker(int failureThreshold, long cooldownMillis) {
        this(failureThreshold, cooldownMillis, System::currentTimeMillis);
    }

    /** 包私有：测试注入可控时钟，精确驱动冷却窗口而不依赖真实时间。 */
    EngineCircuitBreaker(int failureThreshold, long cooldownMillis, LongSupplier clock) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        if (cooldownMillis <= 0) {
            throw new IllegalArgumentException("cooldownMillis must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
        this.clock = clock;
    }

    /**
     * 是否允许本次请求通过。
     * OPEN 且未冷却、或 HALF_OPEN 已有探测在途时返回 false（快速失败）。
     */
    public synchronized boolean allowRequest() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.getAsLong() - openedAtMillis >= cooldownMillis) {
                    state = State.HALF_OPEN;
                    halfOpenProbeInFlight = true; // 本次放行的请求即探测请求
                    return true;
                }
                return false;
            case HALF_OPEN:
                if (halfOpenProbeInFlight) {
                    return false;
                }
                halfOpenProbeInFlight = true;
                return true;
            default:
                return false;
        }
    }

    /** 上游调用成功（含 4xx：客户端错误不视为上游故障）。 */
    public synchronized void onSuccess() {
        switch (state) {
            case CLOSED:
                consecutiveFailures = 0;
                break;
            case HALF_OPEN:
                halfOpenProbeInFlight = false;
                state = State.CLOSED;
                consecutiveFailures = 0;
                break;
            case OPEN:
                // OPEN 态不会放行请求，理论不可达
                break;
            default:
                break;
        }
    }

    /** 上游调用失败（连接失败 / 5xx）。 */
    public synchronized void onFailure() {
        switch (state) {
            case CLOSED:
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    state = State.OPEN;
                    openedAtMillis = clock.getAsLong();
                }
                break;
            case HALF_OPEN:
                halfOpenProbeInFlight = false;
                state = State.OPEN;
                openedAtMillis = clock.getAsLong();
                break;
            case OPEN:
                // OPEN 态不会放行请求，理论不可达
                break;
            default:
                break;
        }
    }

    public synchronized State state() {
        return state;
    }

    /** 当前连续失败计数（仅 CLOSED 态有意义，供测试/观测）。 */
    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}
