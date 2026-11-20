package com.agentflow.workflow.trigger;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.agentflow.workflow.audit.AuditMessage;
import com.agentflow.workflow.audit.AuditPublisher;
import com.agentflow.workflow.execution.ExecutionService;
import com.agentflow.workflow.workflow.WorkflowDefinitionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * 外部触发：webhook 与定时。
 *
 * <p>凭据模型：URL 里的 token 是入口凭据（32 字节随机），secret 用于校验
 * {@code X-AF-Signature: sha256=<hex>}。签名可选——很多调用方签不了——但一旦带上就必须对；
 * 令牌可随时轮换，轮换即让旧链接失效。
 */
@Service
public class TriggerService {

    private static final int MAX_WEBHOOK_PER_MINUTE = 60;
    private static final long SIGNATURE_SKEW_SECONDS = 300;

    private final WorkflowTriggerMapper triggers;
    private final WorkflowDefinitionMapper definitions;
    private final ExecutionService executions;
    private final AuditPublisher audit;
    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public TriggerService(WorkflowTriggerMapper triggers, WorkflowDefinitionMapper definitions,
                          ExecutionService executions, AuditPublisher audit,
                          StringRedisTemplate redis) {
        this.triggers = triggers;
        this.definitions = definitions;
        this.executions = executions;
        this.audit = audit;
        this.redis = redis;
    }

    public record TriggerView(String id, String type, String token, String secret, String cron,
                              String timezone, boolean enabled, Map<String, Object> variables,
                              String versionId, String lastFiredAt, String createdAt) {
    }

    public record FireResult(String executionId, String status, boolean idempotentReplay) {
    }

    public List<TriggerView> list(TenantInfo tenant, String definitionId) {
        requireDefinition(tenant, definitionId);
        return triggers.listByDefinition(definitionId, tenant.tenantId()).stream()
                .map(this::toView)
                .toList();
    }

    public TriggerView create(TenantInfo tenant, String definitionId, String type, String cron,
                              Map<String, Object> variables) {
        if (!tenant.atLeast("EDITOR")) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
        requireDefinition(tenant, definitionId);
        String kind = type == null ? "WEBHOOK" : type.toUpperCase();
        if (!kind.equals("WEBHOOK") && !kind.equals("SCHEDULE")) {
            throw new BusinessException(ErrorCode.WF_STATE_001, "type must be WEBHOOK or SCHEDULE");
        }
        String normalized = kind.equals("SCHEDULE") ? requireCron(cron) : null;
        String id = java.util.UUID.randomUUID().toString();
        var row = new WorkflowTriggerMapper.Row(id, definitionId, tenant.tenantId(), kind,
                newToken(), newSecret(), normalized, "Asia/Shanghai",
                writeJson(variables), null, true, null, tenant.userId(), null, null);
        triggers.insert(row);
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "TRIGGER_CREATED", Map.of("definitionId", definitionId, "triggerId", id,
                        "type", kind, "cron", normalized == null ? "" : normalized)));
        return toView(triggers.findByIdAndTenant(id, tenant.tenantId()));
    }

    public TriggerView patch(TenantInfo tenant, String triggerId, Boolean enabled, String cron,
                             Map<String, Object> variables) {
        if (!tenant.atLeast("EDITOR")) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
        var row = requireTrigger(tenant, triggerId);
        String nextCron = cron != null ? requireCron(cron) : row.cronExpr();
        String nextVariables = variables != null ? writeJson(variables) : row.variablesJson();
        boolean nextEnabled = enabled != null ? enabled : row.enabled();
        triggers.update(triggerId, tenant.tenantId(), nextEnabled, nextCron, nextVariables);
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "TRIGGER_UPDATED", Map.of("triggerId", triggerId, "enabled", nextEnabled)));
        return toView(triggers.findByIdAndTenant(triggerId, tenant.tenantId()));
    }

    /** 轮换令牌：旧 URL 立即失效，签名密钥同步更换。 */
    public TriggerView rotate(TenantInfo tenant, String triggerId) {
        if (!tenant.atLeast("EDITOR")) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
        requireTrigger(tenant, triggerId);
        triggers.rotate(triggerId, tenant.tenantId(), newToken(), newSecret());
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "TRIGGER_ROTATED", Map.of("triggerId", triggerId)));
        return toView(triggers.findByIdAndTenant(triggerId, tenant.tenantId()));
    }

    public void delete(TenantInfo tenant, String triggerId) {
        if (!tenant.atLeast("EDITOR")) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
        requireTrigger(tenant, triggerId);
        triggers.delete(triggerId, tenant.tenantId());
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "TRIGGER_DELETED", Map.of("triggerId", triggerId)));
    }

    /**
     * 公开入口：按 token 找触发器并触发执行。
     *
     * <p>token 不存在一律 404，不区分「不存在」与「已停用」；限流按触发器计数，
     * 避免一个泄露的令牌把整个空间打满。
     */
    public FireResult fireWebhook(String token, String rawBody, String signature,
                                  String timestampHeader, String idempotencyKey) {
        var row = token == null ? null : triggers.findByToken(token);
        if (row == null || !"WEBHOOK".equals(row.triggerType())) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
        if (!row.enabled()) {
            audit.publish(AuditMessage.of(row.tenantId(), null, "system:webhook",
                    "TRIGGER_REJECTED", Map.of("triggerId", row.id(), "reason", "DISABLED")));
            throw new BusinessException(ErrorCode.WF_STATE_001, "trigger is disabled");
        }
        enforceRate(row.id());
        verifySignature(row, rawBody == null ? "" : rawBody, signature, timestampHeader);

        Map<String, Object> payload = readJsonObject(rawBody);
        String key = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey
                : "hook-" + java.util.UUID.randomUUID();
        var result = executions.triggerFromExternal(row.tenantId(), "system:webhook",
                row.definitionId(), row.versionId(), key, merge(row, payload),
                "hook-" + row.id());
        audit.publish(AuditMessage.of(row.tenantId(), result.executionId(), "system:webhook",
                "TRIGGER_FIRED", Map.of("triggerId", row.id(), "type", "WEBHOOK",
                        "signed", signature != null && !signature.isBlank())));
        return new FireResult(result.executionId(), result.status(), result.idempotentReplay());
    }

    /** 入口自检：确认链接有效、是否启用，不返回 secret 与入参。 */
    public Map<String, Object> describe(String token) {
        var row = token == null ? null : triggers.findByToken(token);
        if (row == null || !"WEBHOOK".equals(row.triggerType())) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "WEBHOOK");
        body.put("enabled", row.enabled());
        body.put("definitionId", row.definitionId());
        body.put("createdAt", row.createdAt() == null ? null : row.createdAt().toInstant().toString());
        return body;
    }

    /** 定时扫描的入口：同一分钟内重复扫描用同一个幂等键，不会重复跑。 */    public FireResult fireSchedule(WorkflowTriggerMapper.Row row, Instant now) {
        String minute = ZonedDateTime.ofInstant(now, ZoneId.of(row.timezone()))
                .toLocalDateTime().toString();
        var result = executions.triggerFromExternal(row.tenantId(), "system:schedule",
                row.definitionId(), row.versionId(),
                "sched-" + row.id() + "-" + minute, merge(row, Map.of()),
                "sched-" + row.id());
        triggers.markFired(row.id(), Timestamp.from(now));
        audit.publish(AuditMessage.of(row.tenantId(), result.executionId(), "system:schedule",
                "TRIGGER_FIRED", Map.of("triggerId", row.id(), "type", "SCHEDULE",
                        "cron", row.cronExpr() == null ? "" : row.cronExpr())));
        return new FireResult(result.executionId(), result.status(), result.idempotentReplay());
    }

    /** cron 是否是「到点了」：用上次触发时间（或一分钟前）推算下一次。 */
    public boolean isDue(WorkflowTriggerMapper.Row row, Instant now) {
        if (row.cronExpr() == null || row.cronExpr().isBlank()) {
            return false;
        }
        ZoneId zone = ZoneId.of(row.timezone());
        Instant base = row.lastFiredAt() != null
                ? row.lastFiredAt().toInstant()
                : now.minusSeconds(60);
        try {
            ZonedDateTime next = CronExpression.parse(row.cronExpr())
                    .next(base.atZone(zone));
            return next != null && !next.toInstant().isAfter(now);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public List<WorkflowTriggerMapper.Row> enabledSchedules() {
        return triggers.listEnabledSchedules();
    }

    private Map<String, Object> merge(WorkflowTriggerMapper.Row row, Map<String, Object> payload) {
        Map<String, Object> merged = new LinkedHashMap<>(readJsonObject(row.variablesJson()));
        merged.putAll(payload);
        return merged;
    }

    private void enforceRate(String triggerId) {
        String window = String.valueOf(System.currentTimeMillis() / 1000 / 60);
        String key = "af:hook:rate:" + triggerId + ":" + window;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, java.time.Duration.ofSeconds(120));
        }
        if (count != null && count > MAX_WEBHOOK_PER_MINUTE) {
            throw new BusinessException(ErrorCode.AGT_RATE_LIMIT_001,
                    "webhook rate limit exceeded");
        }
    }

    private void verifySignature(WorkflowTriggerMapper.Row row, String body, String signature,
                                 String timestampHeader) {
        if (signature == null || signature.isBlank()) {
            return;
        }
        if (timestampHeader != null && !timestampHeader.isBlank()) {
            try {
                long ts = Long.parseLong(timestampHeader.trim());
                if (Math.abs(Instant.now().getEpochSecond() - ts) > SIGNATURE_SKEW_SECONDS) {
                    throw new BusinessException(ErrorCode.WF_FORBIDDEN_001,
                            "signature timestamp expired");
                }
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.WF_FORBIDDEN_001,
                        "invalid X-AF-Timestamp");
            }
        }
        String provided = signature.startsWith("sha256=")
                ? signature.substring("sha256=".length()) : signature;
        String expected = hmac(row.secret(), body);
        if (!MessageDigest.isEqual(provided.toLowerCase().getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "signature mismatch");
        }
    }

    private static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot compute signature", e);
        }
    }

    /** 接受 5 段（分 时 日 月 周）与 6 段（含秒）两种写法，统一按秒级解析。 */
    private static String requireCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new BusinessException(ErrorCode.WF_STATE_001, "cron is required for SCHEDULE");
        }
        String normalized = cron.trim().replaceAll("\\s+", " ");
        if (normalized.split(" ").length == 5) {
            normalized = "0 " + normalized;
        }
        try {
            CronExpression.parse(normalized);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.WF_STATE_001,
                    "invalid cron expression: " + e.getMessage());
        }
        return normalized;
    }

    private WorkflowTriggerMapper.Row requireTrigger(TenantInfo tenant, String triggerId) {
        var row = triggers.findByIdAndTenant(triggerId, tenant.tenantId());
        if (row == null) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
        return row;
    }

    private void requireDefinition(TenantInfo tenant, String definitionId) {
        if (definitions.findByIdAndTenant(definitionId, tenant.tenantId()) == null) {
            throw new BusinessException(ErrorCode.WF_NOT_FOUND_001, "resource not found");
        }
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private TriggerView toView(WorkflowTriggerMapper.Row row) {
        return new TriggerView(row.id(), row.triggerType(), row.token(), row.secret(),
                row.cronExpr(), row.timezone(), row.enabled(), readJsonObject(row.variablesJson()),
                row.versionId(),
                row.lastFiredAt() == null ? null : row.lastFiredAt().toInstant().toString(),
                row.createdAt() == null ? null : row.createdAt().toInstant().toString());
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return value == null || value.isEmpty() ? null : json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize trigger variables", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = json.readValue(text, Map.class);
            return parsed instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.WF_STATE_001,
                    "payload must be a JSON object");
        }
    }
}
