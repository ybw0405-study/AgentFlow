package com.agentflow.gateway.config;

import com.agentflow.common.health.HealthController;
import com.agentflow.common.health.HealthProbe;
import com.agentflow.common.health.RedisHealthProbe;
import com.agentflow.common.security.JwtService;
import com.agentflow.common.web.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class GatewayConfiguration {

    @Bean
    public JwtService jwtService(@Value("${agentflow.jwt.secret}") String secret) {
        return new JwtService(secret, 0); // 网关只验签，不签发；ttl 无意义
    }

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /** 网关自身无状态，健康度取决于限流/幂等所依赖的 Redis。 */
    @Bean
    public HealthController healthController(StringRedisTemplate redis) {
        List<HealthProbe> probes = List.of(new RedisHealthProbe(redis));
        return new HealthController("gateway", probes);
    }
}
