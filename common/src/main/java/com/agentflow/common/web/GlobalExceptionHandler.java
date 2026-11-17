package com.agentflow.common.web;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.FieldViolation;
import com.agentflow.common.error.ProblemDetailFactory;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 统一异常 → ProblemDetail（application/problem+json，含 code + traceId）。 */
@RestControllerAdvice
@Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> business(BusinessException ex) {
        ErrorCode code = ex.errorCode();
        ProblemDetail pd = ProblemDetailFactory.create(code, ex.getMessage(), ex.fieldErrors());
        return ResponseEntity.status(code.status()).body(pd);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ProblemDetail> bind(BindException ex) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ProblemDetail pd = ProblemDetailFactory.create(
                ErrorCode.COM_VALIDATION_001, "请求参数校验失败", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> badRequest(Exception ex) {
        // 不回显 ex.getMessage()：其内容可能包含请求体片段（PII/凭据）。仅记入服务端日志。
        log.debug("unreadable/invalid request: {}", ex.toString());
        ProblemDetail pd = ProblemDetailFactory.create(
                ErrorCode.COM_VALIDATION_001, "请求格式错误");
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(NoResourceFoundException ex) {
        ProblemDetail pd = ProblemDetailFactory.create(ErrorCode.COM_NOT_FOUND_001, "资源不存在");
        return ResponseEntity.status(404).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> fallback(Exception ex) {
        log.error("unhandled exception", ex);
        ProblemDetail pd = ProblemDetailFactory.create(ErrorCode.COM_INTERNAL_001, "内部错误");
        return ResponseEntity.status(500).body(pd);
    }
}
