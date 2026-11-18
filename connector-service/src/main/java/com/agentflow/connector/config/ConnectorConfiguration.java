package com.agentflow.connector.config;

import com.agentflow.common.health.DependencyHealth;
import com.agentflow.common.health.HealthController;
import com.agentflow.common.health.HealthProbe;
import com.agentflow.common.health.RedisHealthProbe;
import com.agentflow.common.security.JwtService;
import com.agentflow.common.web.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.connector.crypto.CredentialCipher;
import com.agentflow.connector.service.SandboxPolicy;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 连接器服务装配：JWT 验签、凭据密钥、沙箱策略、横切组件。 */
@Configuration
public class ConnectorConfiguration {

    @Bean
    public JwtService jwtService(@Value("${agentflow.jwt.secret}") String secret) {
        return new JwtService(secret, 0);
    }

    @Bean
    public CredentialCipher credentialCipher(
            @Value("${agentflow.credential.master-key}") String masterKey,
            @Value("${agentflow.credential.previous-keys:}") String previousKeys) {
        java.util.List<String> keys = java.util.Arrays.stream(previousKeys.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new CredentialCipher(masterKey, keys);
    }

    @Bean
    public SandboxPolicy sandboxPolicy(
            @Value("${agentflow.sandbox.allow-insecure-http:false}") boolean allowInsecureHttp,
            @Value("${agentflow.sandbox.allow-loopback:false}") boolean allowLoopback) {
        return new SandboxPolicy(allowInsecureHttp, allowLoopback);
    }

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /** 健康度 = MySQL（连接器与凭据元数据）+ Redis（调用幂等缓存）。 */
    @Bean
    public HealthController healthController(DataSource dataSource, StringRedisTemplate redis) {
        List<HealthProbe> probes = List.of(
                jdbcProbe(dataSource),
                new RedisHealthProbe(redis));
        return new HealthController("connector", probes);
    }

    /** 取一次连接并 isValid(2)：只验证可用性，不触业务读写。 */
    private static HealthProbe jdbcProbe(DataSource dataSource) {
        return () -> {
            try (var connection = dataSource.getConnection()) {
                return connection.isValid(2)
                        ? DependencyHealth.up("mysql")
                        : DependencyHealth.down("mysql", "connection invalid");
            } catch (Exception e) {
                return DependencyHealth.down("mysql", DependencyHealth.summarise(e));
            }
        };
    }
}
