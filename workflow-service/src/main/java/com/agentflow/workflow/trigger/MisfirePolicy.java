package com.agentflow.workflow.trigger;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;

/**
 * 停机 / 故障期间错过的档位怎么处理。
 *
 * <p>三档语义刻意不做成「一个补跑开关」：需要跳过的任务与需要补齐的任务，对「错过」的
 * 理解正好相反，用一个布尔量表达不出来。
 */
public enum MisfirePolicy {

    /** 只补最近一档（默认，V012 起的行为）：停机再久也只跑一次，账面最容易解释。 */
    CATCH_UP_LATEST,

    /** 补齐窗口内错过的每一档，按时间顺序回放；超过 {@code MAX_CATCHUP_SLOTS} 的部分丢弃。 */
    CATCH_UP_ALL,

    /** 错过就放弃，只把游标推到最新档：下午不补发早晨的提醒。 */
    SKIP;

    public static final MisfirePolicy DEFAULT = CATCH_UP_LATEST;

    /** 宽松解析：脏数据 / 老行 / null 一律退回默认策略，不让历史行把扫描打断。 */
    public static MisfirePolicy parseOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    /** 写入前校验：拼错的值直接 409，而不是静默变成别的策略。 */
    public static MisfirePolicy require(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.WF_STATE_001,
                    "misfirePolicy must be one of CATCH_UP_LATEST, CATCH_UP_ALL, SKIP; got "
                            + raw);
        }
    }
}
