package com.agentflow.common.security;

import java.util.Locale;
import java.util.Set;

/**
 * 生产环境配置守护（fail-fast）。
 *
 * <p>本地开发与单元测试默认关闭，无需任何设置，保证演示与测试可运行。
 * 生产环境通过设置环境变量 {@code AF_PRODUCTION=true}
 * （或 JVM 参数 {@code -Dagentflow.production=true}）开启。开启后，任何仍在使用
 * 仓库内 dev 默认值的敏感配置都会在启动期直接拒绝启动，避免公开仓库中的默认
 * 凭证被带入生产。
 */
public final class ProductionGuard {

    public static final String ENV_FLAG = "AF_PRODUCTION";
    public static final String SYS_FLAG = "agentflow.production";

    /** 仓库中出现过的 dev 默认值：JWT 密钥、内部令牌、凭据主密钥（base64）、DB 弱口令。 */
    private static final Set<String> WEAK_VALUES = Set.of(
            "root",
            "agentflow",
            "dev-internal-token",
            "dev-only-secret-key-0123456789abcdef",
            "ZGV2LW9ubHktY3JlZC1tYXN0ZXIta2V5LTMyYnl0ZXM=");

    private ProductionGuard() {
    }

    /** 是否处于生产守护模式。 */
    public static boolean productionEnabled() {
        String flag = System.getenv(ENV_FLAG);
        if (flag == null || flag.isBlank()) {
            flag = System.getProperty(SYS_FLAG);
        }
        return Boolean.parseBoolean(flag);
    }

    /** 生产环境下拒绝 dev 默认值 / 空值；非生产环境直接放行。 */
    public static void requireStrong(String name, String value) {
        if (!productionEnabled()) {
            return;
        }
        if (value == null || value.isBlank()
                || value.toLowerCase(Locale.ROOT).startsWith("dev-")
                || WEAK_VALUES.contains(value.trim())) {
            throw new IllegalStateException(
                    "FAIL-FAST: " + name
                            + " still uses a development default. Provide a strong value in production.");
        }
    }

    /** 生产环境要求指定布尔开关为 true（如 cookie-secure）。 */
    public static void requireTrue(String name, boolean value) {
        if (productionEnabled() && !value) {
            throw new IllegalStateException(
                    "FAIL-FAST: " + name + " must be true in production");
        }
    }

    /** 生产环境要求指定环境变量为 true（如数据库 TLS）。 */
    public static void requireEnvTrue(String name) {
        if (productionEnabled()
                && !Boolean.parseBoolean(System.getenv(name))) {
            throw new IllegalStateException(
                    "FAIL-FAST: " + name + " must be true in production");
        }
    }
}
