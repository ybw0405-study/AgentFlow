package com.agentflow.connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 连接器服务：工具注册、凭据加密托管、出网沙箱、OAuth2 授权。 */
@SpringBootApplication
public class ConnectorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConnectorServiceApplication.class, args);
    }
}
