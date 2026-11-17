package com.agentflow.gateway.config;

import com.agentflow.common.security.JwtService;
import com.agentflow.common.web.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfiguration {

    @Bean
    public JwtService jwtService(@Value("${agentflow.jwt.secret}") String secret) {
        return new JwtService(secret, 0);  // 网关只验签，不签发；ttl 无意义
    }

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
