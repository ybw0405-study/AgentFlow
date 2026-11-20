package com.agentflow.workflow.lease;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 多副本「同一时刻只由一个副本做这件事」的尽力租约（扫描、看护这类后台轮询用）。
 *
 * <p>它不是分布式锁：GET 与 SETNX 之间有极小竞态，抢输的一方也可能认为自己拿到了。
 * 因此它只负责<strong>减少重复劳动</strong>（少扫一遍表、少刷一遍日志），
 * 真正的「只做一次」必须由下游的幂等写入（唯一约束、幂等键、状态机 CAS）保证。
 *
 * <p>Redis 不可用时 fail-open：后台轮询漏做比重复做严重得多。
 */
public class RedisLease {

    private static final Logger log = LoggerFactory.getLogger(RedisLease.class);

    private final StringRedisTemplate redis;
    /** 每个持有者一个 id：续租时用它判断「这把锁是不是我自己持有的」。 */
    private final String holderId = UUID.randomUUID().toString();

    public RedisLease(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 拿/续租约。自己持有则续期，否则抢一次；Redis 故障时返回 true 并告警。
     *
     * @param key 租约键（每个用途一个，互不干扰）
     * @param ttl 租约存活时间，通常覆盖两轮轮询：这一轮异常退出时下一轮仍能接管
     */
    public boolean hold(String key, Duration ttl) {
        try {
            String holder = redis.opsForValue().get(key);
            if (holderId.equals(holder)) {
                redis.expire(key, ttl);
                return true;
            }
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, holderId, ttl));
        } catch (Exception e) {
            log.warn("lease {} unavailable, proceed anyway: {}", key, e.toString());
            return true;
        }
    }
}
