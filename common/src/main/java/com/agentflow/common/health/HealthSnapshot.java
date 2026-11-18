package com.agentflow.common.health;

import java.time.Instant;
import java.util.List;

/** 健康检查响应体。status 取 UP / DEGRADED。 */
public record HealthSnapshot(String status, String service, Instant time,
                             List<DependencyHealth> checks) {
}
