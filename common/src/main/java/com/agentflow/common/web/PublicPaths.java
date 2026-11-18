package com.agentflow.common.web;

import java.util.Set;

/** 免认证白名单：精确匹配，刻意不用通配符。 */
public final class PublicPaths {

    private static final Set<String> PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            // 登录页需要它来渲染演示账号；生产环境恒返回空列表（见 AuthController）
            "/api/v1/auth/bootstrap");

    private PublicPaths() {
    }

    public static boolean isPublic(String path) {
        return PATHS.contains(path);
    }
}
