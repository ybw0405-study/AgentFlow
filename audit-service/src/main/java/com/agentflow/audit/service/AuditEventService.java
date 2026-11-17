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
        Boolean first = redis.opsForValue().setIfAbsent(dedupKey, "1", dedupTtl);
        if (!Boolean.TRUE.equals(first)) {
            return false;
        }
        try {
            mapper.insert(new AuditEventMapper.AuditRow(
                    0L, msg.id(), msg.tenantId(), msg.traceId(), msg.executionId(),
                    msg.actorId(), msg.eventType(),
                    msg.payloadRedacted() == null ? null
                            : objectMapper.writeValueAsString(msg.payloadRedacted()),
                    msg.tokenUsed(), msg.latencyMs(), null));
            return true;
        } catch (DuplicateKeyException e) {
            return false;  // 主键兜底去重
        } catch (Exception e) {
            redis.delete(dedupKey);  // 写入失败放行重试
            log.error("audit insert failed id={} type={}", msg.id(), msg.eventType(), e);
            throw new IllegalStateException("audit insert failed", e);
        }
    }
}
