package com.agentflow.audit.service;

import com.agentflow.audit.repo.AuditEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 审计摄取：Redis 幂等去重（eventId，7d）+ 追加写；重复消息静默吞掉。 */
@Service
public class AuditEventService {

    private static final Logger log = LoggerFactory.getLogger(AuditEventService.class);

    private final AuditEventMapper mapper;
    private final StringRedisTemplate redis;
    private final Duration dedupTtl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditEventService(
            AuditEventMapper mapper, StringRedisTemplate redis,
            @Value("${agentflow.audit.mq-idempotency-ttl-hours:168}") long dedupTtlHours) {
        this.mapper = mapper;
        this.redis = redis;
        this.dedupTtl = Duration.ofHours(dedupTtlHours);
    }

    /** 返回 true = 新写入；false = 重复。 */
    public boolean ingest(AuditMessageDto msg) {
        String dedupKey = "af:audit:seen:" + msg.id();
        // 标记值区分"已确认写库"(DONE) 与"占用中/可能崩溃残留"(PENDING)。
        // 后者不能当成重复丢弃：旧实现 SETNX 后崩溃会让事件永久丢失。
        String seen = redis.opsForValue().get(dedupKey);
        if ("DONE".equals(seen)) {
            return false;
        }
        // 首次到达或残留 PENDING：一律尝试写库，由 DB 主键做权威去重。
        // 用 SETNX 抢占用，仅当 key 不存在时写入 PENDING；抢不到也继续写库（DB 兜底）。
        if (seen == null) {
            redis.opsForValue().setIfAbsent(dedupKey, "PENDING", dedupTtl);
        }
        try {
            mapper.insert(new AuditEventMapper.AuditRow(
                    0L, msg.id(), msg.tenantId(), msg.traceId(), msg.executionId(),
                    msg.actorId(), msg.eventType(),
                    msg.payloadRedacted() == null ? null
                            : objectMapper.writeValueAsString(msg.payloadRedacted()),
                    msg.tokenUsed(), msg.latencyMs(), null));
            // 写库确认成功，原子覆盖为 DONE（保留原 TTL）
            redis.opsForValue().set(dedupKey, "DONE", dedupTtl);
            return true;
        } catch (DuplicateKeyException e) {
            // 主键兜底去重：事件已存在，标记为 DONE 收敛残留的 PENDING
            redis.opsForValue().set(dedupKey, "DONE", dedupTtl);
            return false;
        } catch (Exception e) {
            // 写库失败：删除标记放行重试（若原本是 DONE 被并发覆盖，这里删除后重试仍由主键兜底）
            redis.delete(dedupKey);
            log.error("audit insert failed id={} type={}", msg.id(), msg.eventType(), e);
            throw new IllegalStateException("audit insert failed", e);
        }
    }
}
