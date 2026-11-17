package com.agentflow.common.idempotency;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 实现：af:idem:{tenant}:{scope}:{key}。 */
public class RedisIdempotencyStore implements IdempotencyStore {

    static final String IN_PROGRESS = "__IN_PROGRESS__";

    private final StringRedisTemplate redis;

    public RedisIdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(String tenantId, String scope, String key) {
        return "af:idem:" + tenantId + ":" + scope + ":" + key;
    }

    @Override
    public Optional<String> findResult(String tenantId, String scope, String key) {
        String value = redis.opsForValue().get(key(tenantId, scope, key));
        if (value == null || IN_PROGRESS.equals(value)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    @Override
    public boolean tryStart(String tenantId, String scope, String key, Duration inProgressTtl) {
        Boolean ok = redis.opsForValue().setIfAbsent(
                key(tenantId, scope, key), IN_PROGRESS, inProgressTtl);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void complete(String tenantId, String scope, String key,
                         String resultJson, Duration resultTtl) {
        redis.opsForValue().set(key(tenantId, scope, key), resultJson, resultTtl);
    }

    @Override
    public void clear(String tenantId, String scope, String key) {
        redis.delete(key(tenantId, scope, key));
    }
}
