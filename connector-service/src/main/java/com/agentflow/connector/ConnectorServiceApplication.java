package com.agentflow.connector;

import com.agentflow.common.security.ProductionGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 连接器服务：工具注册、凭据加密托管、出网沙箱、OAuth2 授权。 */
@SpringBootApplication
public class ConnectorServiceApplication {

    public static void main(String[] args) {
        ProductionGuard.requireStrong("AF_JWT_SECRET", System.getenv("AF_JWT_SECRET"));
        ProductionGuard.requireStrong("AF_INTERNAL_TOKEN", System.getenv("AF_INTERNAL_TOKEN"));
        ProductionGuard.requireStrong("AF_CRED_MASTER_KEY", System.getenv("AF_CRED_MASTER_KEY"));
        ProductionGuard.requireStrong("AF_MYSQL_PASSWORD", System.getenv("AF_MYSQL_PASSWORD"));
        ProductionGuard.requireEnvTrue("AF_MYSQL_USE_TLS");
        SpringApplication.run(ConnectorServiceApplication.class, args);
    }
}
