package com.agentflow.common.health;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 探针：一次 PING。超时/异常一律记 down 并保留简短原因。
 *
 * <p>只取异常类型与消息首段——驱动异常里可能带连接串，不能原样出网。
 */
public final class RedisHealthProbe implements HealthProbe {

    private final StringRedisTemplate redis;

    public RedisHealthProbe(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public DependencyHealth check() {
        try {
            String pong = redis.execute(
                    (org.springframework.data.redis.core.RedisCallback<String>) connection -> {
                        connection.ping();
                        return "PONG";
                    },
                    true);
            return "PONG".equals(pong)
                    ? DependencyHealth.up("redis")
                    : DependencyHealth.down("redis", "unexpected ping reply");
        } catch (Exception e) {
            return DependencyHealth.down("redis", DependencyHealth.summarise(e));
        }
    }
}
