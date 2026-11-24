package com.agentflow.gateway.proxy;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * /engine/** 的路径白名单（方法 + 精确路径）。
 *
 * <p>此前 {@code @RequestMapping("/engine/**")} 把任意剩余 URI 原样拼到引擎 base-url：
 * 只要拿着一个共享 X-Internal-Token 就能直达 {@code /internal/events}（全局事件尾读）、
 * {@code /internal/metrics}、{@code /internal/health/ready} 等控制面根本不用的接口，
 * 与 JWT 租户体系完全脱钩。这里按 {@code EngineGatewayClient} 真实调用的方法 + 路径
 * 逐条放行，其余一律 404（不暴露路由存在性）。新增引擎调用点必须同步更新本表与契约测试。
 *
 * <p>注意：白名单不等于授权。真正的租户边界仍由下游服务 / 引擎的空间下推保证，
 * 本表只是把可达面收敛到「控制面确实需要」的最小集合。
 */
public final class EnginePathPolicy {

    private static final String ID =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String SEGMENT = "[^/]+";

    private record Route(String method, Pattern path) {
    }

    private static final List<Route> ALLOWED = List.of(
            new Route("POST", Pattern.compile("^/engine/internal/dsl/validate$")),
            new Route("POST", Pattern.compile("^/engine/internal/executions$")),
            new Route("POST", Pattern.compile("^/engine/internal/nodes/preview$")),
            new Route("GET", Pattern.compile("^/engine/internal/events$")),
            new Route("GET", Pattern.compile("^/engine/internal/executions/" + ID + "/events$")),
            new Route("GET", Pattern.compile("^/engine/internal/executions/" + ID + "/io/" + SEGMENT + "$")),
            new Route("POST", Pattern.compile("^/engine/internal/executions/" + ID + "/cancel$")),
            new Route("GET", Pattern.compile("^/engine/internal/executions/" + ID + "/state$")),
            new Route("GET", Pattern.compile("^/engine/internal/llm/models$")),
            new Route("POST", Pattern.compile("^/engine/internal/executions/" + ID
                    + "/gates/" + SEGMENT + "/resolve$")));

    private EnginePathPolicy() {
    }

    /** 仅当方法 + 路径同时命中白名单才允许代理到引擎。 */
    public static boolean isAllowed(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String normalized = method.toUpperCase(Locale.ROOT);
        for (Route route : ALLOWED) {
            if (route.method().equals(normalized) && route.path().matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 供契约测试与运维核对：白名单条数，防止有人悄悄放宽。 */
    public static int allowlistSize() {
        return ALLOWED.size();
    }
}
