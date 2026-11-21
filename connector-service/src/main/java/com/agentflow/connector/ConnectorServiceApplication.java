package com.agentflow.connector;

import com.agentflow.common.security.ProductionGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

/** 连接器服务：工具注册、凭据加密托管、出网沙箱、OAuth2 授权。 */
@SpringBootApplication
public class ConnectorServiceApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ConnectorServiceApplication.class);
        // AF-4：校验 Spring 实际解析出的属性值，而不是只读环境变量。
        app.addInitializers(context -> {
            Environment env = context.getEnvironment();
            ProductionGuard.requireStrong("agentflow.jwt.secret",
                    env.getProperty("agentflow.jwt.secret"));
            ProductionGuard.requireStrong("agentflow.internal-token",
                    env.getProperty("agentflow.internal-token"));
            ProductionGuard.requireStrong("agentflow.credential.master-key",
                    env.getProperty("agentflow.credential.master-key"));
            ProductionGuard.requireStrong("spring.datasource.password",
                    env.getProperty("spring.datasource.password"));
            ProductionGuard.requireExplicitDbAccount("AF_MYSQL_USER",
                    System.getenv("AF_MYSQL_USER"), env.getProperty("spring.datasource.username"));
            ProductionGuard.requireEnvTrue("AF_MYSQL_USE_TLS");
            ProductionGuard.requireEnvFalse("AF_MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL");
        });
        app.run(args);
    }
}
