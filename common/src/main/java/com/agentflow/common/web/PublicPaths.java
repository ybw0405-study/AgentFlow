package com.agentflow.common.web;

import java.util.List;
import java.util.Set;

/**
 * 免认证白名单。
 *
 * <p>固定路径精确匹配；前缀白名单只给「凭据在路径里」的入口用——webhook 的 token 就是
 * 它的凭据，没办法在登录态里鉴权。除此之外不开放任何通配符：{@code /api/v1/**} 这种宽松
 * 规则一旦加进来，往后新增的接口会默认裸奔。
 */
public final class PublicPaths {

    private static final Set<String> PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            // 登录页需要它来渲染演示账号；生产环境恒返回空列表（见 AuthController）
            "/api/v1/auth/bootstrap");

    private static final List<String> PREFIXES = List.of(
            "/api/v1/hooks/");

    private PublicPaths() {
    }

    public static boolean isPublic(String path) {
        return PATHS.contains(path) || PREFIXES.stream().anyMatch(path::startsWith);
    }
}
