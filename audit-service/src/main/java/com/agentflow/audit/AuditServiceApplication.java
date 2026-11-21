package com.agentflow.audit;

import com.agentflow.common.security.ProductionGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AuditServiceApplication.class);
        // AF-4：校验 Spring 实际解析出的属性值，而不是只读环境变量。
        app.addInitializers(context -> {
            Environment env = context.getEnvironment();
            ProductionGuard.requireStrong("agentflow.jwt.secret",
                    env.getProperty("agentflow.jwt.secret"));
            ProductionGuard.requireStrong("spring.datasource.password",
                    env.getProperty("spring.datasource.password"));
            ProductionGuard.requireExplicitDbAccount("AF_AUDIT_MYSQL_USER",
                    System.getenv("AF_AUDIT_MYSQL_USER"),
                    env.getProperty("spring.datasource.username"));
            ProductionGuard.requireEnvTrue("AF_MYSQL_USE_TLS");
            ProductionGuard.requireEnvFalse("AF_MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL");
        });
        app.run(args);
    }
}
