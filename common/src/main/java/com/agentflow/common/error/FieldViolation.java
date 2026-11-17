package com.agentflow.common.error;

/** 字段级校验错误。 */
public record FieldViolation(String field, String message) {
}
