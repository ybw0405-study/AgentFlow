package com.agentflow.common.idempotency;

import java.time.Duration;
import java.util.Optional;

/** 幂等键存储：Redis 快速通道（in-progress 60s / result 24h）；DB 唯一约束是兜底。 */
public interface IdempotencyStore {

    Optional<String> findResult(String tenantId, String scope, String key);

    /** 抢占执行权；false = 已存在（进行中或已完成）。 */
    boolean tryStart(String tenantId, String scope, String key, Duration inProgressTtl);

    void complete(String tenantId, String scope, String key, String resultJson, Duration resultTtl);

    void clear(String tenantId, String scope, String key);
}
