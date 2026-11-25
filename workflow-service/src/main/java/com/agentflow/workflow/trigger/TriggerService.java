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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private static final Logger log = LoggerFactory.getLogger(TriggerService.class);
    private static final int MAX_WEBHOOK_PER_MINUTE = 60;
    /** claim 的存活时间：覆盖一轮扫描 + 一次派发，过期后下一分钟自然换了 key。 */
    private static final Duration CLAIM_TTL = Duration.ofMinutes(2);
    private static final long SIGNATURE_SKEW_SECONDS = 300;
    /**
     * 补跑窗口：停机再久，也只往前找 24 小时内的档位。
     *
     * <p>它同时是循环的上界——每分钟一档的 cron 最多回看 1440 档，扫描一轮不会失控。
     * 窗口外的错过直接放弃：业务方看到「补跑了一次最近的」，比看到几十条迟到执行更容易解释。
     */
    private static final Duration CATCHUP_WINDOW = Duration.ofHours(24);
    /** 兜底上界，防止窗口与 MAX 不一致时退化成死循环。 */
    private static final int MAX_SLOTS_PER_SCAN = 1500;
    /**
     * {@code CATCH_UP_ALL} 一轮最多回放多少档。
     *
     * <p>「全部补齐」必须有界：每分钟一档的任务停机 3 小时就是 180 次执行，瞬间打满并发与额度。
     * 超出部分丢弃并记审计（{@code skipped}），补的是最近的这一段——最近的数据通常更要紧。
     */
    private static final int MAX_CATCHUP_SLOTS = 20;
    /** 超过 1 分钟就算补偿跑，落审计时带上延迟秒数。 */
    private static final long LATE_THRESHOLD_SECONDS = 60;
    private static final DateTimeFormatter MINUTE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECOND_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkflowTriggerMapper triggers;
    private final WorkflowDefinitionMapper definitions;
    private final ExecutionService executions;
    private final AuditPublisher audit;
    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    /** 触发器默认时区（ARCH-6）：生产由配置注入，单测 new 时保持历史默认值兼容。 */
    @Value("${agentflow.trigger.default-timezone:Asia/Shanghai}")
    private String defaultTimezone = "Asia/Shanghai";

    public TriggerService(WorkflowTriggerMapper triggers, WorkflowDefinitionMapper definitions,
                          ExecutionService executions, AuditPublisher audit,
                          StringRedisTemplate redis) {
        this.triggers = triggers;
        this.definitions = definitions;
        this.executions = executions;
        this.audit = audit;
        this.redis = redis;
    }

    /**
     * 触发器的对外视图。
     *
     * <p>两种时间串刻意不一样：{@code lastFiredAt} / {@code createdAt} 是**真实发生过的瞬间**，
     * 用 ISO-8601（UTC）交给前端按浏览器时区渲染；{@code lastSlotAt} / {@code nextFireAt} /
     * {@code nextFireTimes} 是 **cron 的墙钟档位**，按触发器自己的时区格式化，调用方要连
     * {@code timezone} 一起看——「每天 09:00」指的是那个时区的 09:00，不是 UTC 的。
     *
     * <p>{@code lastOutcome} 回答「上一次扫描到底跑了没有」：{@code SKIP} 时
     * {@code lastFiredAt} 保持不变（跳过不是触发），只有 {@code lastSlotAt} 前进。
     */
    /**
     * 触发器视图。{@code secret} 仅在 create/rotate 的一次性响应里回明文，其余端点为 null；
     * {@code hasSecret} 告诉前端「密钥已设置，需轮换才能再次查看」（SEC-3）。
     */
    public record TriggerView(String id, String type, String token, String secret,
                              boolean hasSecret, String cron,
                              String timezone, boolean enabled, String misfirePolicy,
                              Map<String, Object> variables, String versionId,
                              String lastFiredAt, String lastSlotAt, String lastOutcome,
                              String nextFireAt, List<String> nextFireTimes, String createdAt) {
    }

    /** cron 预览：不改任何状态，只回答「这条 cron 接下来会在这几个时间点跑」。 */
    public record CronPreview(String cron, String timezone, List<String> times) {
    }

    /**
     * 一轮扫描该干什么。
     *
     * <p>{@code slots} 是要派发的档位（按时间升序）；{@code consumeTo} 非空表示「一档都不跑，
     * 只把游标推到这一档」（SKIP 策略）；{@code skipped} 是这一批里没有派发的档数，用于审计与
     * 自我解释——用户问「为什么只跑了一次」，答案就在这个数字里。
     */
    public record SlotPlan(List<Instant> slots, Instant consumeTo, int skipped) {
        public static final SlotPlan NONE = new SlotPlan(List.of(), null, 0);

        public boolean isEmpty() {
            return slots.isEmpty() && consumeTo == null;
        }
    }

    public record FireResult(String executionId, String status, boolean idempotentReplay) {
    }

    public List<TriggerView> list(TenantInfo tenant, String definitionId) {
        requireDefinition(tenant, definitionId);
        return triggers.listByDefinition(definitionId, tenant.tenantId()).stream()
                .map(row -> toView(row, false))
                .toList();
    }

    public TriggerView create(TenantInfo tenant, String definitionId, String type, String cron,
                              String misfirePolicy, Map<String, Object> variables) {
        if (!tenant.atLeast("EDITOR")) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
        requireDefinition(tenant, definitionId);
        String kind = type == null ? "WEBHOOK" : type.toUpperCase();
        if (!kind.equals("WEBHOOK") && !kind.equals("SCHEDULE")) {
            throw new BusinessException(ErrorCode.WF_STATE_001, "type must be WEBHOOK or SCHEDULE");
        }
        String normalized = kind.equals("SCHEDULE") ? requireCron(cron) : null;
        String policy = kind.equals("SCHEDULE")
                ? MisfirePolicy.require(misfirePolicy).name()
                : MisfirePolicy.DEFAULT.name();
        String id = java.util.UUID.randomUUID().toString();
        var row = new WorkflowTriggerMapper.Row(id, definitionId, tenant.tenantId(), kind,
                newToken(), newSecret(), normalized, defaultTimezone,
                writeJson(variables), null, true, policy, null, null, null, tenant.userId(),
                null, null);
        triggers.insert(row);
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "TRIGGER_CREATED", Map.of("definitionId", definitionId, "triggerId", id,
                        "type", kind, "cron", normalized == null ? "" : normalized,
                        "misfirePolicy", policy)));
        return toView(triggers.findByIdAndTenant(id, tenant.tenantId()), true);
    }

    public TriggerView patch(TenantInfo tenant, String triggerId, Boolean enabled, String cron,
                             String misfirePolicy, Map<String, Object> variables) {
        if (!tenant.atLeast("EDITOR")) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "insufficient role");
        }
        var row = requireTrigger(tenant, triggerId);
        String nextCron = cron != null ? requireCron(cron) : row.cronExpr();
        String nextVariables = variables != null ? writeJson(variables) : row.variablesJson();
        boolean nextEnabled = enabled != null ? enabled : row.enabled();
        String nextPolicy = misfirePolicy != null
                ? MisfirePolicy.require(misfirePolicy).name()
                : MisfirePolicy.parseOrDefault(row.misfirePolicy()).name();
        triggers.update(triggerId, tenant.tenantId(), nextEnabled, nextCron, nextPolicy,
                nextVariables);
        audit.publish(AuditMessage.of(tenant.tenantId(), null, tenant.userId(),
                "TRIGGER_UPDATED", Map.of("triggerId", triggerId, "enabled", nextEnabled,
                        "misfirePolicy", nextPolicy)));
        return toView(triggers.findByIdAndTenant(triggerId, tenant.tenantId()), false);
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
        return toView(triggers.findByIdAndTenant(triggerId, tenant.tenantId()), true);
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

    /**
     * 定时扫描的入口：跑的是 {@code slot} 那一档，而不是「现在」。
     *
     * <p>幂等键用档位（{@code sched-{id}-{档位}}）而不是当前分钟，是为了让「派发成功、
     * 记账前进程被杀」这种中断可恢复：重启后 {@code last_fired_at} 没变，重算出的还是同一档，
     * 于是走幂等回放（{@code idempotentReplay=true}）而不是再跑一遍。
     */
    public FireResult fireSchedule(WorkflowTriggerMapper.Row row, Instant now, Instant slot) {
        return fireSchedule(row, now, slot, 0);
    }

    /** {@code skipped} 是这一批补跑里被放弃的档数：写进审计，事后能解释「为什么只跑了几次」。 */
    public FireResult fireSchedule(WorkflowTriggerMapper.Row row, Instant now, Instant slot,
                                   int skipped) {
        Objects.requireNonNull(slot, "slot");
        String slotStamp = slotOf(row.timezone(), slot, isSubMinute(row.cronExpr()));
        long lateSeconds = Math.max(0, Duration.between(slot, now).getSeconds());
        var result = executions.triggerFromExternal(row.tenantId(), "system:schedule",
                row.definitionId(), row.versionId(),
                "sched-" + row.id() + "-" + slotStamp, merge(row, Map.of()),
                "sched-" + row.id());
        triggers.markFired(row.id(), Timestamp.from(now), Timestamp.from(slot));
        audit.publish(AuditMessage.of(row.tenantId(), result.executionId(), "system:schedule",
                "TRIGGER_FIRED", Map.of("triggerId", row.id(), "type", "SCHEDULE",
                        "cron", row.cronExpr() == null ? "" : row.cronExpr(),
                        "slot", slotStamp,
                        "lateSeconds", lateSeconds,
                        "skipped", skipped,
                        "policy", MisfirePolicy.parseOrDefault(row.misfirePolicy()).name(),
                        "compensated", lateSeconds >= LATE_THRESHOLD_SECONDS)));
        if (lateSeconds >= LATE_THRESHOLD_SECONDS) {
            log.info("schedule trigger {} fired late: slot={} late={}s idempotentReplay={}",
                    row.id(), slotStamp, lateSeconds, result.idempotentReplay());
        }
        return new FireResult(result.executionId(), result.status(), result.idempotentReplay());
    }

    /**
     * {@code SKIP} 策略：一档都不跑，只把游标推到最新错过的那一档。
     *
     * <p>不推游标会让每轮扫描都重新算出同一批过期档位，等于永远卡在「本该跳过」的状态里；
     * 而 {@code lastFiredAt} 必须原样保留——它是「真的跑过一次」的事实，跳过不是触发。
     */
    public void skipMissed(WorkflowTriggerMapper.Row row, Instant now, SlotPlan plan) {
        Instant latest = plan.consumeTo();
        Objects.requireNonNull(latest, "consumeTo");
        String slotStamp = slotOf(row.timezone(), latest, isSubMinute(row.cronExpr()));
        triggers.markSkipped(row.id(), Timestamp.from(latest));
        audit.publish(AuditMessage.of(row.tenantId(), null, "system:schedule", "TRIGGER_SKIPPED",
                Map.of("triggerId", row.id(), "type", "SCHEDULE",
                        "cron", row.cronExpr() == null ? "" : row.cronExpr(),
                        "slot", slotStamp,
                        "missed", plan.skipped(),
                        "policy", MisfirePolicy.SKIP.name())));
        log.info("schedule trigger {} skipped {} missed slot(s): latest={} scanAt={}",
                row.id(), plan.skipped(), slotStamp, now);
    }

    /** 这条工作流的并发余量：{@code limit <= 0} 表示不限。 */
    public record Capacity(int limit, int active) {
        public boolean available() {
            return limit <= 0 || active < limit;
        }
    }

    /**
     * 还能不能再起一个执行。
     *
     * <p>调度器用它来决定「这一档现在跑，还是留到下一轮」。查不到定义时返回不限：
     * 定义被删是派发阶段该报的错，不要在这里伪装成「并发满了」。
     */
    public Capacity capacity(WorkflowTriggerMapper.Row row) {
        var def = definitions.findByIdAndTenant(row.definitionId(), row.tenantId());
        if (def == null || def.maxConcurrent() <= 0) {
            return new Capacity(0, 0);
        }
        return new Capacity(def.maxConcurrent(), executions.activeCount(def.id()));
    }

    /** 派发阶段撞上并发上限（预检查与真正插入之间被别的触发抢先时的兜底）。 */
    public static boolean isConcurrencyLimit(Throwable error) {
        return error instanceof BusinessException e
                && e.errorCode() == ErrorCode.WF_CONCURRENCY_001;
    }

    /**
     * 到并发上限：剩下的档位这一轮不跑，**游标也不动**。
     *
     * <p>不动游标是关键：下一轮扫描会重新算出同一批档位，等有执行跑完就继续补。
     * 这与 {@code skipped} 是两件事——跳过是「不要了」，推迟是「等会儿还要」，
     * 所以记的是 {@code TRIGGER_DEFERRED} 而不是把档位算进丢弃数。
     */
    public void defer(WorkflowTriggerMapper.Row row, SlotPlan plan, int fired, Capacity capacity,
                      Instant now) {
        int pending = plan.slots().size() - fired;
        audit.publish(AuditMessage.of(row.tenantId(), null, "system:schedule",
                "TRIGGER_DEFERRED", Map.of("triggerId", row.id(),
                        "definitionId", row.definitionId(),
                        "policy", MisfirePolicy.parseOrDefault(row.misfirePolicy()).name(),
                        "fired", fired,
                        "pending", pending,
                        "limit", capacity.limit(),
                        "active", capacity.active())));
        log.info("schedule trigger {} deferred {} slot(s) at concurrency limit {} (active={}),"
                + " fired={}", row.id(), pending, capacity.limit(), capacity.active(), fired);
    }

    /**
     * 幂等键里的「分钟」：必须先截断到分钟。
     *
     * <p>两个副本在不同秒扫到同一条触发器时（07 秒 / 41 秒），带秒的时间串会生成两个不同的
     * 幂等键，于是各派发一次——账面两次执行，用户只配了一条 cron。截到分钟才是「这一分钟
     * 只跑一次」。
     */
    static String minuteOf(String timezone, Instant now) {
        return ZonedDateTime.ofInstant(now, ZoneId.of(timezone))
                .truncatedTo(ChronoUnit.MINUTES)
                .toLocalDateTime()
                .toString();
    }

    /**
     * 幂等键里的「档位串」：秒级 cron 精确到秒，分钟级 cron 仍截到分钟。
     *
     * <p>分钟级 cron 的档位本来就在整分上，截到分钟与截到秒结果一致（整分的
     * {@code LocalDateTime.toString()} 不带秒）；秒级 cron 必须带秒，否则同一分钟内的多个
     * 档位会撞出同一个幂等键，账面只剩一条执行。两个副本在不同秒扫到同一档位时，档位串
     * 相同，仍指向同一条执行。
     */
    static String slotOf(String timezone, Instant slot, boolean subMinute) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(slot, ZoneId.of(timezone));
        return subMinute ? zdt.truncatedTo(ChronoUnit.SECONDS).toLocalDateTime().toString()
                : minuteOf(timezone, slot);
    }

    /**
     * cron 是否秒级触发：6 段且秒位非 {@code 0}。
     *
     * <p>5 段写法归一到秒位 {@code 0}，视为分钟级；6 段里秒位是 {@code 0} 也仍是分钟级。
     * 秒位只要是「每 5 秒一档」「第 30 秒」这类 0 之外的写法，都按秒级处理——
     * 幂等键与 claim 精确到秒，不会把同分钟内的不同档位吞掉。
     */
    static boolean isSubMinute(String cronExpr) {
        if (cronExpr == null || cronExpr.isBlank()) {
            return false;
        }
        String[] parts = cronExpr.trim().split("\\s+");
        return parts.length == 6 && !"0".equals(parts[0]);
    }

    /**
     * 抢占本次扫描的触发权：多副本同时扫描时只有一个真正派发。
     *
     * <p>键精确到秒而不是分钟：分钟级 claim 会让秒级 cron 在同一分钟内被第一个副本
     * claim 后、接下来 2 分钟（{@link #CLAIM_TTL}）整条触发器都不再派发，秒档位被静默吞掉。
     * 秒级键下这只是「尽力而为」的优化——正确性仍由幂等键（触发器 + 档位）保证，两个副本
     * 跨秒扫到同一档位时，后者走幂等回放而不是再跑一遍。Redis 不可用时 fail-open（返回 true），
     * 漏跑的代价远大于多试一次。
     */
    public boolean claimFire(String triggerId, Instant now) {
        String key = "af:sched:claim:" + triggerId + ":" + now.getEpochSecond();
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", CLAIM_TTL));
        } catch (Exception e) {
            log.warn("schedule claim unavailable, fall back to idempotency key: {}", e.toString());
            return true;
        }
    }

    /**
     * 该跑但还没跑的那些档：不到点返回 {@link SlotPlan#NONE}。
     *
     * <p>起点是「档位游标」——{@code last_slot_at}，它为空时才退回 {@code last_fired_at}
     * （新触发器则是「一分钟前」，所以创建后不会立刻炸一次）。只用档位而不是「两者较大值」，
     * 是因为 {@code last_fired_at} 记的是扫描时刻：补跑到一半被并发上限挡住时，它会跑到
     * 未跑档位的前面去，取较大值就等于把剩下的档位丢了（见 {@link #cursorOf}）。
     * 起点早于补跑窗口时截到窗口边界。
     *
     * <p>之后按 {@link MisfirePolicy} 决定交出去哪些档：只补最近一档、补齐最近 N 档，或者一档都不补
     * 只推游标。三种策略共用同一段扫描，差别仅在收尾——这样「哪一档算错过」的口径只有一份。
     */
    public SlotPlan dueSlots(WorkflowTriggerMapper.Row row, Instant now) {
        if (row.cronExpr() == null || row.cronExpr().isBlank()) {
            return SlotPlan.NONE;
        }
        final CronExpression cron;
        try {
            cron = parseCron(row.cronExpr());
        } catch (IllegalArgumentException e) {
            return SlotPlan.NONE;
        }
        Instant floor = now.minus(CATCHUP_WINDOW);
        Instant base = cursorOf(row, now);
        if (base.isBefore(floor)) {
            base = floor;
        }
        ZoneId zone = ZoneId.of(row.timezone());
        List<Instant> missed = new ArrayList<>();
        ZonedDateTime cursor = base.atZone(zone);
        for (int i = 0; i < MAX_SLOTS_PER_SCAN; i++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null || next.toInstant().isAfter(now)) {
                break;
            }
            missed.add(next.toInstant());
            cursor = next;
        }
        if (missed.isEmpty()) {
            return SlotPlan.NONE;
        }
        Instant latest = missed.get(missed.size() - 1);
        // 首次到点不算「错过」：新建的触发器无论什么策略都该跑当前这一档
        MisfirePolicy policy = row.lastFiredAt() == null && row.lastSlotAt() == null
                ? MisfirePolicy.CATCH_UP_LATEST
                : MisfirePolicy.parseOrDefault(row.misfirePolicy());
        return switch (policy) {
            case SKIP -> new SlotPlan(List.of(), latest, missed.size());
            case CATCH_UP_LATEST -> new SlotPlan(List.of(latest), null, missed.size() - 1);
            case CATCH_UP_ALL -> {
                int dropped = Math.max(0, missed.size() - MAX_CATCHUP_SLOTS);
                yield new SlotPlan(List.copyOf(missed.subList(dropped, missed.size())), null,
                        dropped);
            }
        };
    }

    /** 便捷封装：只要最近那一档（{@code SKIP} 或不到点时返回 null）。 */
    public Instant dueSlot(WorkflowTriggerMapper.Row row, Instant now) {
        List<Instant> slots = dueSlots(row, now).slots();
        return slots.isEmpty() ? null : slots.get(slots.size() - 1);
    }

    /**
     * 扫描游标：最后**消费掉**的档位。
     *
     * <p>优先用 {@code last_slot_at}（档位），只有它为空时才退回 {@code last_fired_at}
     * （真实发生时刻，V012 之前的历史行只有这个）。
     *
     * <p>这里曾经取两者较大值，看起来更「安全」，实际上会在补跑中途推迟时丢档：
     * 跑完第 1 档时 {@code last_fired_at} 记的是**扫描时刻**（12:46），而第 2 档的档位是 12:44，
     * 取较大值就把 12:44~12:46 全跳过了。档位游标天然就是「最后消费掉的档位」，
     * 用它当基准既能让 SKIP 前进，也不会让 {@code CATCH_UP_ALL} 的半途而废变成永久丢失。
     */
    private static Instant cursorOf(WorkflowTriggerMapper.Row row, Instant now) {
        if (row.lastSlotAt() != null) {
            return row.lastSlotAt().toInstant();
        }
        if (row.lastFiredAt() != null) {
            return row.lastFiredAt().toInstant();
        }
        return now.minusSeconds(60);
    }

    /** 接下来 count 个触发点（触发器时区的墙钟串）；cron 非法时返回空表而不是抛错。 */
    public static List<String> nextSlots(String cron, String timezone, Instant from, int count) {
        List<String> times = new ArrayList<>();
        if (cron == null || cron.isBlank() || count <= 0) {
            return times;
        }
        final CronExpression expression;
        final ZoneId zone;
        try {
            expression = parseCron(cron);
            zone = ZoneId.of(timezone);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            return times;
        }
        ZonedDateTime cursor = from.atZone(zone);
        boolean subMinute = isSubMinute(cron);
        DateTimeFormatter format = subMinute ? SECOND_FORMAT : MINUTE_FORMAT;
        for (int i = 0; i < count; i++) {
            ZonedDateTime next = expression.next(cursor);
            if (next == null) {
                break;
            }
            times.add(format.format(next));
            cursor = next;
        }
        return times;
    }

    /** 保存前先看一眼：这条 cron 接下来会在这几个时间点跑。 */
    public CronPreview previewCron(TenantInfo tenant, String definitionId, String cron,
                                   String timezone, Integer count) {
        requireDefinition(tenant, definitionId);
        String normalized = requireCron(cron);
        String zone = timezone == null || timezone.isBlank() ? defaultTimezone : timezone;
        try {
            ZoneId.of(zone);
        } catch (java.time.DateTimeException e) {
            throw new BusinessException(ErrorCode.WF_STATE_001, "unknown timezone: " + zone);
        }
        int size = count == null ? 5 : Math.min(Math.max(count, 1), 20);
        return new CronPreview(normalized, zone, nextSlots(normalized, zone, Instant.now(), size));
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
        // 带签名必须带时间戳，且时间戳参与 HMAC：否则抓包后去掉 X-AF-Timestamp
        // 即可绕过 300s 防重放窗口，同一签名永久有效。
        if (timestampHeader == null || timestampHeader.isBlank()) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001,
                    "X-AF-Timestamp is required when a signature is provided");
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "invalid X-AF-Timestamp");
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > SIGNATURE_SKEW_SECONDS) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001, "signature timestamp expired");
        }
        String provided = signature.startsWith("sha256=")
                ? signature.substring("sha256=".length()) : signature;
        String expected = hmac(row.secret(), timestampHeader.trim() + "." + body);
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

    /** Spring 的 CronExpression 只认 6 段（秒 分 时 日 月 周）：5 段写法在解析前补上秒位 0。 */
    private static CronExpression parseCron(String cron) {
        String normalized = cron.trim().replaceAll("\\s+", " ");
        if (normalized.split(" ").length == 5) {
            normalized = "0 " + normalized;
        }
        return CronExpression.parse(normalized);
    }

    /**
     * 接受 5 段（分 时 日 月 周）与 6 段（含秒）两种写法，统一按 6 段解析。
     *
     * <p>秒位非 0 即秒级触发：幂等键与 claim 都精确到秒（见 {@link #slotOf} 与
     * {@link #claimFire}）。准点精度仍受扫描间隔（{@code agentflow.trigger.poll-interval-ms}）
     * 约束——落在同一次扫描间隔内的多个秒档位会被一次性补回，而不是毫秒级实时；
     * 需要更密准点时把扫描间隔调小即可，与「分钟粒度的有界补偿」是同一套机制。
     */
    private static String requireCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new BusinessException(ErrorCode.WF_STATE_001, "cron is required for SCHEDULE");
        }
        String normalized = cron.trim().replaceAll("\\s+", " ");
        if (normalized.split(" ").length == 5) {
            normalized = "0 " + normalized;
        }
        try {
            parseCron(normalized);
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

    private TriggerView toView(WorkflowTriggerMapper.Row row, boolean includeSecret) {
        boolean scheduled = "SCHEDULE".equals(row.triggerType());
        List<String> next = scheduled
                ? nextSlots(row.cronExpr(), row.timezone(), Instant.now(), 3) : List.of();
        boolean present = row.secret() != null && !row.secret().isBlank();
        return new TriggerView(row.id(), row.triggerType(), row.token(),
                includeSecret && present ? row.secret() : null, present,
                row.cronExpr(), row.timezone(), row.enabled(),
                MisfirePolicy.parseOrDefault(row.misfirePolicy()).name(),
                readJsonObject(row.variablesJson()),
                row.versionId(),
                row.lastFiredAt() == null ? null : row.lastFiredAt().toInstant().toString(),
                row.lastSlotAt() == null ? null
                        : slotText(row.timezone(), row.lastSlotAt(), isSubMinute(row.cronExpr())),
                row.lastOutcome(),
                next.isEmpty() ? null : next.get(0), next,
                row.createdAt() == null ? null : row.createdAt().toInstant().toString());
    }

    /** 档位按触发器时区渲染：它本来就是墙钟，换算成浏览器时区反而看不懂。 */
    private static String slotText(String timezone, Timestamp slot, boolean subMinute) {
        return (subMinute ? SECOND_FORMAT : MINUTE_FORMAT)
                .format(slot.toInstant().atZone(ZoneId.of(timezone)));
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
