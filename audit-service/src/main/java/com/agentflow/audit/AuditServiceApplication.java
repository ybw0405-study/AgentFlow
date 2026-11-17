package com.agentflow.audit;

import com.agentflow.common.security.ProductionGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        ProductionGuard.requireStrong("AF_JWT_SECRET", System.getenv("AF_JWT_SECRET"));
        ProductionGuard.requireStrong("AF_MYSQL_PASSWORD", System.getenv("AF_MYSQL_PASSWORD"));
        ProductionGuard.requireEnvTrue("AF_MYSQL_USE_TLS");
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
