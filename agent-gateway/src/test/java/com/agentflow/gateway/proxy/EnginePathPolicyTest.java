package com.agentflow.gateway.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 白名单必须恰好覆盖控制面真实调用，多一条都算放宽了攻击面。 */
class EnginePathPolicyTest {

    private static final String ID = UUID.randomUUID().toString();

    @Test
    void everyControlPlaneRouteIsAllowed() {
        List<String[]> routes = List.of(
                new String[]{"POST", "/engine/internal/dsl/validate"},
                new String[]{"POST", "/engine/internal/executions"},
                new String[]{"POST", "/engine/internal/nodes/preview"},
                new String[]{"GET", "/engine/internal/events"},
                new String[]{"GET", "/engine/internal/executions/" + ID + "/events"},
                new String[]{"GET", "/engine/internal/executions/" + ID + "/io/node_a"},
                new String[]{"POST", "/engine/internal/executions/" + ID + "/cancel"},
                new String[]{"GET", "/engine/internal/executions/" + ID + "/state"},
                new String[]{"GET", "/engine/internal/llm/models"},
                new String[]{"POST", "/engine/internal/executions/" + ID + "/gates/g1/resolve"});
        for (String[] route : routes) {
            assertTrue(EnginePathPolicy.isAllowed(route[0], route[1]),
                    route[0] + " " + route[1] + " must be reachable");
        }
        assertEquals(routes.size(), EnginePathPolicy.allowlistSize());
    }

    @Test
    void globalReadMetricsAndProbesAreNotExposed() {
        for (String path : List.of(
                "/engine/internal/metrics",
                "/engine/internal/health",
                "/engine/internal/health/live",
                "/engine/internal/health/ready",
                "/engine/internal/mq/status",
                "/engine/internal/executions/" + ID + "/io/../../events")) {
            assertFalse(EnginePathPolicy.isAllowed("GET", path), path);
            assertFalse(EnginePathPolicy.isAllowed("POST", path), path);
        }
    }

    @Test
    void wrongMethodIsRejectedEvenOnAnAllowedPath() {
        assertFalse(EnginePathPolicy.isAllowed("DELETE", "/engine/internal/executions"));
        assertFalse(EnginePathPolicy.isAllowed("GET", "/engine/internal/dsl/validate"));
        assertFalse(EnginePathPolicy.isAllowed(null, "/engine/internal/events"));
        assertFalse(EnginePathPolicy.isAllowed("GET", null));
    }

    @Test
    void queryStringIsNotPartOfThePathAndNonUuidIdsAreRejected() {
        assertFalse(EnginePathPolicy.isAllowed("GET", "/engine/internal/executions/not-a-uuid/state"));
        assertFalse(EnginePathPolicy.isAllowed("GET", "/engine/internal/executions/" + ID + "/state?x=1"));
        // 真实请求里 getRequestURI() 不含 query；这里显式确认调用方传入的是纯路径
        assertTrue(EnginePathPolicy.isAllowed("GET", "/engine/internal/executions/" + ID + "/state"));
    }
}
