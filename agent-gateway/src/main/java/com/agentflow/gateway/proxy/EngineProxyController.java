package com.agentflow.gateway.proxy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * /engine/** 手工反向代理（HTTP/1.1）。
 * 原因：gateway-mvc 的 JDK HttpClient 默认 HTTP/2 并对上游发起 h2c 升级，
 * 而引擎（uvicorn）只说 HTTP/1.1 → 400。引擎路由全部是短 JSON 请求，手工代理可控。
 *
 * <p> 信任边界：客户端提供的内部令牌 / 用户凭据一律**剥离**，改由服务端注入
 * 引擎专用凭据 {@code agentflow.engine.internal-token}。即便调用方持有控制面共享令牌，
 * 也无法拿它直连引擎；配合 {@link EnginePathPolicy} 的路径白名单，可达面收敛到
 * 控制面实际用到的最小集合。
 */
@RestController
public class EngineProxyController {

    private static final Set<String> HOP_HEADERS = Set.of(
            "host", "connection", "content-length", "transfer-encoding", "upgrade",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer");

    /** 客户端身份/内部凭据：绝不向引擎透传，防止凭据外泄与「带令牌直达引擎」。 */
    private static final Set<String> STRIPPED_CLIENT_HEADERS = Set.of(
            "x-internal-token", "authorization", "cookie", "x-api-key", "proxy-authorization");

    private final RestClient http;
    private final String engineToken;

    public EngineProxyController(
            @Value("${agentflow.engine.base-url:http://127.0.0.1:8000}") String engineBase,
            @Value("${agentflow.engine.internal-token}") String engineToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(30_000);
        this.http = RestClient.builder().baseUrl(engineBase).requestFactory(factory).build();
        this.engineToken = engineToken;
    }

    /**
     * 组装出站头：先过滤 hop-by-hop 与客户端凭据，再由服务端注入引擎专用令牌。
     * 抽成静态方法便于契约测试直接断言「客户端令牌确实被剥掉、只留一个注入值」。
     */
    static HttpHeaders buildOutboundHeaders(HttpServletRequest request, String engineToken) {
        HttpHeaders out = new HttpHeaders();
        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HOP_HEADERS.contains(lower) && !STRIPPED_CLIENT_HEADERS.contains(lower)) {
                out.set(name, request.getHeader(name));
            }
        });
        out.set("X-Internal-Token", engineToken);
        return out;
    }

    @RequestMapping("/engine/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body)
            throws IOException {
        String path = request.getRequestURI().substring("/engine".length());
        String query = request.getQueryString();
        String uri = path + (query != null ? "?" + query : "");

        HttpHeaders outbound = buildOutboundHeaders(request, engineToken);
        var spec = http.method(HttpMethod.valueOf(request.getMethod())).uri(uri)
                .headers(out -> out.putAll(outbound));
        if (body != null && body.length > 0) {
            spec.body(body);
        }
        return spec.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                .headers(res.getHeaders())
                .body(res.bodyTo(byte[].class)));
    }
}
