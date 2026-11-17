package com.agentflow.gateway;

import com.agentflow.common.security.ProductionGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        ProductionGuard.requireStrong("AF_JWT_SECRET", System.getenv("AF_JWT_SECRET"));
        ProductionGuard.requireStrong("AF_INTERNAL_TOKEN", System.getenv("AF_INTERNAL_TOKEN"));
        SpringApplication.run(GatewayApplication.class, args);
    }
}
