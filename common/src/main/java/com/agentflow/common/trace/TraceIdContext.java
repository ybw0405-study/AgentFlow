package com.agentflow.common.trace;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** traceId 上下文：W3C traceparent 优先，兼容 X-Trace-Id（与引擎侧 trace.py 对齐）。 */
public final class TraceIdContext {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER_TRACEPARENT = "traceparent";
    public static final String HEADER_X_TRACE_ID = "X-Trace-Id";

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private TraceIdContext() {
    }

    public static String currentOrCreate() {
        String current = MDC.get(MDC_KEY);
        return current != null ? current : generate();
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 构造 W3C traceparent（新 span id）。 */
    public static String newTraceparent(String traceId) {
        String span = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + span + "-01";
    }

    /** 从请求头解析；非法或全零一律视为不存在。 */
    public static String resolve(String traceparent, String xTraceId) {
        if (traceparent != null) {
            var m = TRACEPARENT.matcher(traceparent);
            if (m.matches() && !"0".repeat(32).equals(m.group(1))) {
                return m.group(1);
            }
        }
        if (xTraceId != null && TRACE_ID.matcher(xTraceId).matches()
                && !"0".repeat(32).equals(xTraceId)) {
            return xTraceId;
        }
        return generate();
    }
}
