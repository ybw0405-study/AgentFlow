package com.agentflow.workflow;

import com.agentflow.common.security.ProductionGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WorkflowServiceApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(WorkflowServiceApplication.class);
        // AF-4：校验 Spring 实际解析出的属性值，而不是只读环境变量。
        app.addInitializers(context -> {
            Environment env = context.getEnvironment();
            ProductionGuard.requireStrong("agentflow.jwt.secret",
                    env.getProperty("agentflow.jwt.secret"));
            ProductionGuard.requireStrong("agentflow.gateway.internal-token",
                    env.getProperty("agentflow.gateway.internal-token"));
            ProductionGuard.requireStrong("spring.datasource.password",
                    env.getProperty("spring.datasource.password"));
            // 运行时账号必须显式注入且等于实际生效值（拦住 --xxx / SPRING_APPLICATION_JSON 覆盖）
            ProductionGuard.requireExplicitDbAccount("AF_MYSQL_USER",
                    System.getenv("AF_MYSQL_USER"), env.getProperty("spring.datasource.username"));
            ProductionGuard.requireTrue("agentflow.jwt.cookie-secure",
                    Boolean.parseBoolean(env.getProperty("agentflow.jwt.cookie-secure", "false")));
            ProductionGuard.requireEnvTrue("AF_MYSQL_USE_TLS");
            ProductionGuard.requireEnvFalse("AF_MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL");
        });
        app.run(args);
    }
}
