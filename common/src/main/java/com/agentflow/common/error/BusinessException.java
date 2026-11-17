package com.agentflow.common.error;

import java.util.List;

/** 业务异常：携带错误码 + 细节 + 字段级错误。 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<FieldViolation> fieldErrors;

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
    }

    public BusinessException(ErrorCode errorCode, String detail, List<FieldViolation> fieldErrors) {
        super(detail);
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldViolation> fieldErrors() {
        return fieldErrors;
    }
}
