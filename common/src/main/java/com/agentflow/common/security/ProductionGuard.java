package com.agentflow.common.security;

import java.util.Locale;
import java.util.Set;

/**
 * 生产环境配置守护（fail-fast）。
 *
 * <p>本地开发与单元测试无需任何设置，保证演示与测试可运行；但「未显式声明」不等于
 * 「可以裸奔」。以下任一信号出现即进入守护模式：
 * <ol>
 *   <li>{@code AF_PRODUCTION=true} 环境变量；</li>
 *   <li>{@code -Dagentflow.production=true} JVM 参数；</li>
 *   <li>{@code AF_ENV} 为 {@code production} / {@code prod} / {@code staging}；</li>
 *   <li>{@code SPRING_PROFILES_ACTIVE} 含 {@code prod}（Spring 部署的通行约定）。</li>
 * </ol>
 *
 * <p>进入守护后，任何仍在使用仓库内 dev 默认值的敏感配置都会在启动期直接拒绝启动，
 * 避免公开仓库中的默认凭证被带入生产。
 */
public final class ProductionGuard {

    public static final String ENV_FLAG = "AF_PRODUCTION";
    public static final String SYS_FLAG = "agentflow.production";
    public static final String ENV_NAME = "AF_ENV";

    /** 仓库中出现过的 dev 默认值：JWT 密钥、内部令牌、凭据主密钥（base64）、DB 弱口令。 */
    private static final Set<String> WEAK_VALUES = Set.of(
            "root",
            "agentflow",
            "dev-internal-token",
            "dev-only-secret-key-0123456789abcdef",
            "ZGV2LW9ubHktY3JlZC1tYXN0ZXIta2V5LTMyYnl0ZXM=");

    /** 明确表示非生产的取值：出现时即使其他信号命中也不守护（便于本地以 prod profile 调试）。 */
    private static final Set<String> DEV_MARKERS = Set.of("dev", "local", "test", "development");

    private ProductionGuard() {
    }

    /** 是否处于生产守护模式。 */
    public static boolean productionEnabled() {
        String envName = System.getenv(ENV_NAME);
        if (envName != null && DEV_MARKERS.contains(envName.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }

        String flag = System.getenv(ENV_FLAG);
        if (flag == null || flag.isBlank()) {
            flag = System.getProperty(SYS_FLAG);
        }
        if (Boolean.parseBoolean(flag)) {
            return true;
        }

        if (envName != null) {
            String normalized = envName.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("production") || normalized.equals("prod")
                    || normalized.equals("staging")) {
                return true;
            }
        }

        String profiles = System.getenv("SPRING_PROFILES_ACTIVE");
        if (profiles != null) {
            for (String profile : profiles.split(",")) {
                if (profile.trim().toLowerCase(Locale.ROOT).startsWith("prod")) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * 生产环境要求指定环境变量明确为 false（如 MySQL 的 allowPublicKeyRetrieval）。
     * 未设置视为 false（安全默认）；仅当显式设为 true 时拒绝启动。
     */
    public static void requireEnvFalse(String name) {
        String value = System.getenv(name);
        if (productionEnabled() && Boolean.parseBoolean(value)) {
            throw new IllegalStateException(
                    "FAIL-FAST: " + name + " must be false/absent in production");
        }
    }

    /**
     * AF-3：网关→引擎凭据必须与控制面凭据分离。只做「注入」而不强制分离的话，
     * 拿到控制面共享令牌的人依然可以绕开网关直连引擎 /internal/**。
     */
    public static void requireDistinct(String name, String controlPlaneToken,
                                       String engineToken) {
        if (!productionEnabled()) {
            return;
        }
        if (controlPlaneToken == null || engineToken == null
                || controlPlaneToken.isBlank() || engineToken.isBlank()
                || controlPlaneToken.equals(engineToken)) {
            throw new IllegalStateException(
                    "FAIL-FAST: " + name
                            + " must be a strong value distinct from the control-plane token (AF-3)");
        }
    }

    /**
     * AF-4：生产环境数据源账号必须由环境变量**显式注入**，且与 Spring 实际解析出的
     * datasource username 完全一致、不得为 root。
     *
     * <p>只校验 System.getenv 会漏掉两种绕过：{@code --spring.datasource.username=root}
     * 命令行覆盖，以及 {@code SPRING_APPLICATION_JSON} 注入。这里同时比对实际生效值，
     * 覆盖后与 env 不一致一律拒绝启动，least-privilege.sql 的 GRANT 才不会形同虚设。
     */
    public static void requireExplicitDbAccount(String envName, String explicitValue,
                                                 String resolvedValue) {
        if (!productionEnabled()) {
            return;
        }
        if (explicitValue == null || explicitValue.isBlank()
                || resolvedValue == null || resolvedValue.isBlank()
                || !explicitValue.equals(resolvedValue)
                || "root".equalsIgnoreCase(resolvedValue.trim())) {
            throw new IllegalStateException(
                    "FAIL-FAST: " + envName + " must explicitly name the least-privilege DB account "
                            + "that Spring actually uses (not root, not overridden via CLI/JSON)");
        }
    }
}
