package com.agentflow.gateway;

import com.agentflow.common.security.ProductionGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(GatewayApplication.class);
        // 在 Environment 就绪、Bean 创建之前校验**实际生效值**。只读
        // System.getenv 会被 --xxx / SPRING_APPLICATION_JSON 覆盖回弱值而不被察觉。
        app.addInitializers(context -> {
            Environment env = context.getEnvironment();
            String controlPlaneToken = env.getProperty("agentflow.internal-token");
            String engineToken = env.getProperty("agentflow.engine.internal-token");
            ProductionGuard.requireStrong("agentflow.jwt.secret",
                    env.getProperty("agentflow.jwt.secret"));
            ProductionGuard.requireStrong("agentflow.internal-token", controlPlaneToken);
            ProductionGuard.requireStrong("agentflow.engine.internal-token", engineToken);
            // 生产环境网关注入的引擎凭据必须与控制面凭据不同
            ProductionGuard.requireDistinct("AF_ENGINE_INTERNAL_TOKEN",
                    controlPlaneToken, engineToken);
        });
        app.run(args);
    }
}
