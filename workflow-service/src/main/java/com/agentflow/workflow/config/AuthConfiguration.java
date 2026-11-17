package com.agentflow.workflow.config;

import com.agentflow.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 认证相关 Bean。 */
@Configuration
public class AuthConfiguration {

    @Bean
    public JwtService jwtService(@Value("${agentflow.jwt.secret}") String secret,
                                 @Value("${agentflow.jwt.ttl-seconds}") long ttlSeconds) {
        return new JwtService(secret, ttlSeconds * 1000);
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder(
            @Value("${agentflow.jwt.bcrypt-strength}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
