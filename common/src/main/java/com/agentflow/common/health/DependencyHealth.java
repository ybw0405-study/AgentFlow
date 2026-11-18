package com.agentflow.common.health;

/** 单项依赖的探活结果。detail 仅在 down 时给出原因，避免正常路径泄露连接信息。 */
public record DependencyHealth(String name, boolean up, String detail) {

    public static DependencyHealth up(String name) {
        return new DependencyHealth(name, true, null);
    }

    public static DependencyHealth down(String name, String detail) {
        return new DependencyHealth(name, false, detail);
    }

    /**
     * 把异常压成一行可读原因。
     *
     * <p>驱动异常的消息里常带完整连接串或账号，健康检查是未认证端点，
     * 因此只取首行并截断，且生产环境由 {@code HealthController} 统一抹掉明细。
     */
    public static String summarise(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String firstLine = message.lines().findFirst().orElse("");
        return firstLine.length() > 120 ? firstLine.substring(0, 120) : firstLine;
    }
}
