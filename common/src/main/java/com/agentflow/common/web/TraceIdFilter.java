package com.agentflow.common.web;

import com.agentflow.common.trace.TraceIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/** 每请求解析/生成 traceId，注入 MDC 并回写响应头。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String previous = MDC.get(TraceIdContext.MDC_KEY);
        String traceId = TraceIdContext.resolve(
                request.getHeader(TraceIdContext.HEADER_TRACEPARENT),
                request.getHeader(TraceIdContext.HEADER_X_TRACE_ID));
        MDC.put(TraceIdContext.MDC_KEY, traceId);
        response.setHeader(TraceIdContext.HEADER_X_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous != null) {
                MDC.put(TraceIdContext.MDC_KEY, previous);
            } else {
                MDC.remove(TraceIdContext.MDC_KEY);
            }
        }
    }
}
