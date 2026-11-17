package com.agentflow.common.web;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.trace.TraceIdContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;

/**
 * 过滤器内直接写 ProblemDetail（过滤器抛出的异常到不了 @RestControllerAdvice）。
 *
 * <p>注意：不能用 {@code ObjectMapper.writeValue(ProblemDetail)} —— 普通 ObjectMapper 缺少
 * Spring 的 ProblemDetail mixin，会把扩展属性塞进 {@code properties} 子对象，导致过滤器路径
 * 与 controller 路径的错误体结构不一致。这里显式构造契约字段（§7：code + traceId 在顶层）。
 */
public final class FilterExceptionWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FilterExceptionWriter() {
    }

    public static void write(HttpServletResponse response, ErrorCode code, String detail)
            throws java.io.IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.problemType());
        body.put("title", code.title());
        body.put("status", code.status().value());
        body.put("detail", detail);
        body.put("code", code.code());
        body.put("traceId", TraceIdContext.currentOrCreate());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
