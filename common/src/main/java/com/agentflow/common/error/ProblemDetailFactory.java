package com.agentflow.common.error;

import com.agentflow.common.trace.TraceIdContext;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** ProblemDetail 统一构造：扩展属性 code + traceId（§7 契约）。 */
public final class ProblemDetailFactory {

    private static final int MAX_DETAIL = 1000;
    private static final int MAX_ERRORS = 20;

    private ProblemDetailFactory() {
    }

    public static ProblemDetail create(ErrorCode code, String detail, List<FieldViolation> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), clamp(detail));
        pd.setType(java.net.URI.create(code.problemType()));
        pd.setTitle(code.title());
        pd.setProperty("code", code.code());
        pd.setProperty("traceId", TraceIdContext.currentOrCreate());
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors);
        }
        return pd;
    }

    public static ProblemDetail create(ErrorCode code, String detail) {
        return create(code, detail, null);
    }

    public static ProblemDetail of(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, clamp(detail));
        pd.setTitle(title);
        pd.setProperty("code", code);
        pd.setProperty("traceId", TraceIdContext.currentOrCreate());
        return pd;
    }

    private static String clamp(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
    }
}
