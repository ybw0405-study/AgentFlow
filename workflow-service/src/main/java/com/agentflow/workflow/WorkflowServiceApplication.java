package com.agentflow.workflow;

import com.agentflow.common.security.ProductionGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WorkflowServiceApplication {

    public static void main(String[] args) {
        ProductionGuard.requireStrong("AF_JWT_SECRET", System.getenv("AF_JWT_SECRET"));
        ProductionGuard.requireStrong("AF_INTERNAL_TOKEN", System.getenv("AF_INTERNAL_TOKEN"));
        ProductionGuard.requireStrong("AF_MYSQL_PASSWORD", System.getenv("AF_MYSQL_PASSWORD"));
        ProductionGuard.requireTrue("AF_COOKIE_SECURE",
                Boolean.parseBoolean(System.getenv("AF_COOKIE_SECURE")));
        ProductionGuard.requireEnvTrue("AF_MYSQL_USE_TLS");
        SpringApplication.run(WorkflowServiceApplication.class, args);
    }
}
