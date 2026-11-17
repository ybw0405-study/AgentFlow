package com.agentflow.gateway.proxy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
 */
@RestController
public class EngineProxyController {

    private static final List<String> HOP_HEADERS = List.of(
            "host", "connection", "content-length", "transfer-encoding", "upgrade",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer");

    private final RestClient http;

    public EngineProxyController(
            @Value("${agentflow.engine.base-url:http://127.0.0.1:8000}") String engineBase) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(30_000);
        this.http = RestClient.builder().baseUrl(engineBase).requestFactory(factory).build();
    }

    @RequestMapping("/engine/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body)
            throws IOException {
        String path = request.getRequestURI().substring("/engine".length());
        String query = request.getQueryString();
        String uri = path + (query != null ? "?" + query : "");

        var spec = http.method(HttpMethod.valueOf(request.getMethod())).uri(uri)
                .headers(out -> request.getHeaderNames().asIterator().forEachRemaining(name -> {
                    if (!HOP_HEADERS.contains(name.toLowerCase())) {
                        out.set(name, request.getHeader(name));
                    }
                }));
        if (body != null && body.length > 0) {
            spec.body(body);
        }
        return spec.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                .headers(res.getHeaders())
                .body(res.bodyTo(byte[].class)));
    }
}
