package com.agentflow.workflow.gate;

import com.agentflow.common.error.BusinessException;
import com.agentflow.workflow.engine.EngineGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 裁决重投（outbox relay）：每 5 秒取到期记录重试引擎。
 *
 * <p>退避 = min(10s × attempts, 300s)，20 次仍失败则置 FAILED 并停止重试
 * （此时只剩人工介入：执行已挂起、裁决未生效，运维可从审计与 outbox 定位）。
 */
@Component
public class GateResolveRelay {

    private static final Logger log = LoggerFactory.getLogger(GateResolveRelay.class);
    private static final int MAX_ATTEMPTS = 20;
    private static final int BATCH = 20;

    private final GateResolveOutboxMapper outbox;
    private final EngineGatewayClient engine;

    public GateResolveRelay(GateResolveOutboxMapper outbox, EngineGatewayClient engine) {
        this.outbox = outbox;
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${agentflow.gate.relay-interval-ms:5000}",
            initialDelay = 8000)
    public void relay() {
        for (var row : outbox.due(BATCH)) {
            try {
                engine.resolveGate(row.executionId(), row.nodeKey(), row.decision(),
                        row.resolvedBy(), row.comment());
                outbox.markDone(row.id());
                log.info("gate resolve replayed execution={} node={}", row.executionId(),
                        row.nodeKey());
            } catch (BusinessException e) {
                int attempts = row.attempts() + 1;
                boolean giveUp = attempts >= MAX_ATTEMPTS;
                String status = giveUp ? "FAILED" : "PENDING";
                int delay = (int) Math.min(10L * attempts, 300L);
                outbox.markRetry(row.id(), status, attempts,
                        truncate(e.getMessage()), delay);
                log.warn("gate resolve replay failed execution={} node={} attempts={} ({})",
                        row.executionId(), row.nodeKey(), attempts, e.getMessage());
            }
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
