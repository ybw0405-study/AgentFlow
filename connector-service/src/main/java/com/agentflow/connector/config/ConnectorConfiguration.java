package com.agentflow.connector.config;

import com.agentflow.common.security.JwtService;
import com.agentflow.common.web.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.connector.crypto.CredentialCipher;
import com.agentflow.connector.service.SandboxPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
