package com.agentflow.common.api;

import com.agentflow.common.trace.TraceIdContext;

/** 成功响应统一信封；错误一律走 ProblemDetail（不含本信封）。 */
public record ApiResponse<T>(String code, String msg, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "success", data, TraceIdContext.currentOrCreate());
    }
}
