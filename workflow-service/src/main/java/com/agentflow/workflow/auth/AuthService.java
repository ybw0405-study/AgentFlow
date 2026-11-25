package com.agentflow.workflow.auth;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.JwtService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** 登录认证：恒定时间（未知账号走 dummy 哈希比对）+ 内存登录限流（5 次/60s）。 */
@Service
public class AuthService {

    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7ZBUYbI2fUL5jH9hTDN6Q8eQZbB5D7K";
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 60_000;
    /** 超过这个数才做一次全量过期回收：避免每次失败登录都 O(n) 遍历整表。 */
    private static final int PRUNE_THRESHOLD = 1_024;
    /** 硬上限：活跃 key 真的撑到这个量级时整表清空兜底，宁可短暂放开这一层也不撑爆堆。 */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder;
    private final JwtService jwtService;
    private final long ttlSeconds;
    private final Map<String, Window> failures = new ConcurrentHashMap<>();

    public AuthService(UserMapper userMapper, BCryptPasswordEncoder encoder,
                       JwtService jwtService,
                       @org.springframework.beans.factory.annotation.Value(
                               "${agentflow.jwt.ttl-seconds:900}") long ttlSeconds) {
        this.userMapper = userMapper;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.ttlSeconds = ttlSeconds;
    }

    public record LoginResult(String userId, String accessToken, long expiresIn) {
    }

    /** 为已认证用户直接签发（refresh 路径，不走登录限流）。 */
    public LoginResult issueFor(UserMapper.UserRow user) {
        List<String> roles = "ADMIN".equals(user.platformRole())
                ? List.of("USER", "ADMIN") : List.of("USER");
        return new LoginResult(user.id(), jwtService.issue(user.id(), roles), ttlSeconds);
    }

    public LoginResult login(String email, String password, String remoteKey) {
        checkRateLimit(remoteKey);
        UserMapper.UserRow user = userMapper.findByEmail(email);
        // 恒定时间：账号不存在也比对一次 dummy，不产生用户枚举信号
        boolean ok = encoder.matches(password,
                user != null ? user.passwordHash() : DUMMY_HASH);
        if (user == null || !ok || !"ACTIVE".equals(user.status())) {
            recordFailure(remoteKey);
            throw new BusinessException(ErrorCode.USR_AUTH_001, "invalid credentials");
        }
        failures.remove(remoteKey);
        return issueFor(user);
    }

    private void checkRateLimit(String key) {
        Window w = failures.get(key);
        if (w != null && w.inWindow() && w.count.get() >= MAX_FAILURES) {
            throw new BusinessException(ErrorCode.USR_RATE_LIMIT_001, "too many attempts");
        }
    }

    private void recordFailure(String key) {
        pruneExpired();
        failures.compute(key, (k, w) -> {
            if (w == null || !w.inWindow()) {
                return new Window();
            }
            w.count.incrementAndGet();
            return w;
        });
    }

    /**
     * 过期窗口回收：key 含调用方可控的 email 前缀，若只增不删，攻击者用不同邮箱
     * 慢慢刷就能让堆无限涨。常态下只清理过期项；真被活跃 key 撑到硬上限时整表清空兜底
     * （网关还有第二层限流，短暂放开这一层可接受）。
     */
    private void pruneExpired() {
        if (failures.size() < PRUNE_THRESHOLD) {
            return;
        }
        failures.entrySet().removeIf(e -> !e.getValue().inWindow());
        if (failures.size() >= MAX_TRACKED_KEYS) {
            failures.clear();
            log.warn("login failure map hit cap {}; cleared to bound memory", MAX_TRACKED_KEYS);
        }
    }

    private static final class Window {
        private final long startedAt = System.currentTimeMillis();
        private final AtomicInteger count = new AtomicInteger(1);

        boolean inWindow() {
            return System.currentTimeMillis() - startedAt < WINDOW_MS;
        }
    }
}
