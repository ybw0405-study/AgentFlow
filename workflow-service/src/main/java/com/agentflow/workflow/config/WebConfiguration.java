package com.agentflow.workflow.config;

import com.agentflow.common.health.DependencyHealth;
import com.agentflow.common.health.HealthController;
import com.agentflow.common.health.HealthProbe;
import com.agentflow.common.health.RedisHealthProbe;
import com.agentflow.common.web.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 注册 common 横切组件（TraceIdFilter 最高优先级；全局异常处理）与健康检查。 */
@Configuration
public class WebConfiguration {

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * 健康度 = MySQL（业务状态）+ Redis（限流、幂等、SSE 扇出）。
     * 任一不可用即返回 503，避免把读不到库的实例留在负载均衡后面。
     */
    @Bean
    public HealthController healthController(DataSource dataSource, StringRedisTemplate redis) {
        List<HealthProbe> probes = List.of(jdbcProbe(dataSource, "mysql"), new RedisHealthProbe(redis));
        return new HealthController("workflow", probes);
    }

    /**
     * 取一次连接并 isValid(2)。用 isValid 而非执行业务 SQL，避免健康检查本身
     * 成为慢查询或写入源；探针放在服务侧而非 common，保持 common 不依赖 java.sql。
     */
    static HealthProbe jdbcProbe(DataSource dataSource, String name) {
        return () -> {
            try (var connection = dataSource.getConnection()) {
                return connection.isValid(2)
                        ? DependencyHealth.up(name)
                        : DependencyHealth.down(name, "connection invalid");
            } catch (Exception e) {
                return DependencyHealth.down(name, DependencyHealth.summarise(e));
            }
        };
    }
}
