package com.agentflow.common.health;

import com.agentflow.common.security.ProductionGuard;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点 {@code GET /health}。
 *
 * <p>语义区分（符合容器探针惯例）：
 * <ul>
 * <li>{@code UP} + 200：进程存活且声明的依赖均可用；</li>
 * <li>{@code DEGRADED} + 503：进程存活但至少一个依赖不可用，不应接收流量。</li>
 * </ul>
 *
 * <p>生产环境不返回依赖明细，避免把内部拓扑（库地址、驱动错误）暴露给未认证调用方；
 * 详情仅回落到服务端日志。
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final String service;
    private final List<HealthProbe> probes;

    public HealthController(String service, List<HealthProbe> probes) {
        this.service = service;
        this.probes = List.copyOf(probes);
    }

    @GetMapping("/health")
    public ResponseEntity<HealthSnapshot> health() {
        List<DependencyHealth> checks = probes.stream().map(HealthProbe::check).toList();
        boolean healthy = checks.stream().allMatch(DependencyHealth::up);
        if (!healthy) {
            log.warn("health check degraded: {}",
                    checks.stream().filter(c -> !c.up())
                            .map(c -> c.name() + "=" + c.detail()).toList());
        }
        boolean revealDetail = !ProductionGuard.productionEnabled();
        List<DependencyHealth> body = revealDetail
                ? checks
                : checks.stream()
                        .map(c -> new DependencyHealth(c.name(), c.up(), c.up() ? null : "unavailable"))
                        .toList();
        HealthSnapshot snapshot = new HealthSnapshot(
                healthy ? "UP" : "DEGRADED", service, Instant.now(), body);
        return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(snapshot);
    }
}
