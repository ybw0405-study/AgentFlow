package com.agentflow.common.error;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * API 错误码目录（architecture.md §8）。码形：2-4 位前缀-语义段-3 位数字。
 * 执行内部错误码（EXEC-BUDGET_EXHAUSTED 等）出现在事件流中，不在此枚举。
 */
public enum ErrorCode {
    COM_VALIDATION_001("COM-VALIDATION-001", HttpStatus.BAD_REQUEST, "validation", "请求校验失败"),
    COM_NOT_FOUND_001("COM-NOT_FOUND-001", HttpStatus.NOT_FOUND, "not-found", "资源不存在"),
    COM_INTERNAL_001("COM-INTERNAL-001", HttpStatus.INTERNAL_SERVER_ERROR, "internal", "内部错误"),
    COM_CONFLICT_001("COM-CONFLICT-001", HttpStatus.CONFLICT, "conflict", "请求冲突"),

    USR_AUTH_001("USR-AUTH-001", HttpStatus.UNAUTHORIZED, "auth", "认证失败"),
    USR_TOKEN_001("USR-TOKEN-001", HttpStatus.UNAUTHORIZED, "token", "令牌无效或已过期"),
    USR_RATE_LIMIT_001("USR-RATE_LIMIT-001", HttpStatus.TOO_MANY_REQUESTS, "rate-limit", "请求过于频繁"),

    AGT_AUTH_001("AGT-AUTH-001", HttpStatus.UNAUTHORIZED, "auth", "未认证"),
    AGT_RATE_LIMIT_001("AGT-RATE_LIMIT-001", HttpStatus.TOO_MANY_REQUESTS, "rate-limit", "触发限流"),
    AGT_UPSTREAM_001("AGT-UPSTREAM-001", HttpStatus.BAD_GATEWAY, "upstream", "执行引擎暂不可用"),
    AGT_UPSTREAM_003("AGT-UPSTREAM-003", HttpStatus.SERVICE_UNAVAILABLE, "kill-switch", "AI 能力已被管理员关闭"),

    WF_NOT_FOUND_001("WF-NOT_FOUND-001", HttpStatus.NOT_FOUND, "not-found", "资源不存在"),
    WF_FORBIDDEN_001("WF-FORBIDDEN-001", HttpStatus.FORBIDDEN, "forbidden", "权限不足"),
    WF_IMMUTABLE_001("WF-IMMUTABLE-001", HttpStatus.CONFLICT, "immutable", "已冻结版本不可修改"),
    WF_STATE_001("WF-STATE-001", HttpStatus.CONFLICT, "state", "非法状态转移"),
    WF_CONCURRENCY_001("WF-CONCURRENCY-001", HttpStatus.TOO_MANY_REQUESTS, "concurrency",
            "并发执行已达上限"),
    WF_BUDGET_001("WF-BUDGET-001", HttpStatus.TOO_MANY_REQUESTS, "budget-exceeded",
            "月度调用/额度已用尽，新的执行被拒绝"),
    WF_POLICY_CLAMPED_001("WF-POLICY_CLAMPED-001", HttpStatus.OK, "policy-clamped", "policy 被下调至租户上限"),

    DSL_VALIDATION_001("DSL-VALIDATION-001", HttpStatus.UNPROCESSABLE_ENTITY, "dsl", "DSL 校验失败"),
    DSL_CYCLE_001("DSL-CYCLE-001", HttpStatus.UNPROCESSABLE_ENTITY, "dsl-cycle", "DSL 存在环"),
    DSL_REF_001("DSL-REF-001", HttpStatus.UNPROCESSABLE_ENTITY, "dsl-ref", "DSL 引用错误"),
    DSL_ORPHAN_001("DSL-ORPHAN-001", HttpStatus.UNPROCESSABLE_ENTITY, "dsl-orphan", "DSL 存在不可达节点"),
    DSL_BRANCH_001("DSL-BRANCH-001", HttpStatus.UNPROCESSABLE_ENTITY, "dsl-branch", "DSL 分支配置错误"),

    AUD_APPENDONLY_001("AUD-APPENDONLY-001", HttpStatus.INTERNAL_SERVER_ERROR, "audit", "审计存储被非法修改");

    /** 枚举常量初始化早于静态字段，故 pattern 放进 holder。 */
    private static final class Patterns {
        private static final Pattern CODE = Pattern.compile("^[A-Z]{2,4}-[A-Z_]+-[0-9]{3}$");
    }

    private final String code;
    private final HttpStatus status;
    private final String problemSlug;
    private final String title;

    ErrorCode(String code, HttpStatus status, String problemSlug, String title) {
        if (!Patterns.CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("malformed error code: " + code);
        }
        this.code = code;
        this.status = status;
        this.problemSlug = problemSlug;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String problemType() {
        return "https://agentflow.example/problems/" + problemSlug;
    }

    public String title() {
        return title;
    }

    public static Optional<ErrorCode> find(String code) {
        for (ErrorCode c : values()) {
            if (c.code.equals(code)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
