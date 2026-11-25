package com.agentflow.workflow.execution;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import com.agentflow.workflow.workflow.WorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 版本灰度发布（P1）：按比例把执行路由到 canary 版本；异常可一键回滚。
 *
 * 配置存 Redis（af:canary:{definitionId}），不污染业务库 schema。
 * - primary/canary 必须是该 definition 下真实存在的版本；
 * - 显式指定 versionId 的触发不受灰度影响（便于固定版本回归）；
 * - percent=0 等价于回滚到 primary。
 */
@Service
public class CanaryService {

    private static final String KEY_PREFIX = "af:canary:";
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final WorkflowVersionMapper versions;
    private final WorkflowDefinitionMapper definitions;
    private final ObjectMapper objectMapper;

    public CanaryService(StringRedisTemplate redis, WorkflowVersionMapper versions,
                         WorkflowDefinitionMapper definitions, ObjectMapper objectMapper) {
        this.redis = redis;
        this.versions = versions;
        this.definitions = definitions;
        this.objectMapper = objectMapper;
    }

    public record Config(String primaryVersionId, String canaryVersionId, int percent) {}

    /** 配置灰度：校验两个版本都属于该 definition。 */
    public Config configure(String definitionId, String primaryVersionId,
                            String canaryVersionId, int percent, String tenantId) {
        requireOwned(definitionId, tenantId);
        if (percent < 0 || percent > 100) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "percent must be 0..100");
        }
        requireVersion(definitionId, primaryVersionId);
        requireVersion(definitionId, canaryVersionId);
        Config cfg = new Config(primaryVersionId, canaryVersionId, percent);
        write(definitionId, cfg);
        return cfg;
    }

    /** 一键回滚：percent 置 0，所有新执行走 primary。 */
    public Config rollback(String definitionId, String tenantId) {
        Config current = read(definitionId);
        if (current == null) {
            throw new BusinessException(ErrorCode.WF_STATE_001, "no canary config to roll back");
        }
        Config rolled = new Config(current.primaryVersionId(), current.canaryVersionId(), 0);
        write(definitionId, rolled);
        return rolled;
    }

    public Config get(String definitionId, String tenantId) {
        requireOwned(definitionId, tenantId);
        return read(definitionId);
    }

    /** 灰度分流：未配置或无 versionId 时返回 null（调用方回退 current）；
     * 返回分流决策（实际版本 id + 是否 canary + percent），供审计。 */
    public Decision route(String definitionId, String currentVersionId) {
        Config cfg = read(definitionId);
        if (cfg == null || cfg.percent() == 0) {
            return null;
        }
        boolean canary = ThreadLocalRandom.current().nextInt(100) < cfg.percent();
        String chosen = canary ? cfg.canaryVersionId() : cfg.primaryVersionId();
        // 配置中的版本可能已被清理；回退到 current
        if (versions.findById(chosen) == null) {
            chosen = currentVersionId;
            canary = false;
        }
        return new Decision(chosen, canary, cfg.percent());
    }

    public record Decision(String versionId, boolean canary, int percent) {}

    private void write(String definitionId, Config cfg) {
        try {
            redis.opsForValue().set(KEY_PREFIX + definitionId, objectMapper.writeValueAsString(cfg), TTL);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "cannot persist canary config");
        }
    }

    private Config read(String definitionId) {
        String json = redis.opsForValue().get(KEY_PREFIX + definitionId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Config.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void requireOwned(String definitionId, String tenantId) {
        if (definitions.findByIdAndTenant(definitionId, tenantId) == null) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
    }

    private void requireVersion(String definitionId, String versionId) {
        var v = versions.findById(versionId);
        if (v == null || !definitionId.equals(v.definitionId())) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "version not found");
        }
    }
}
