package com.agentflow.workflow.auth;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.JwtService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** 登录认证：恒定时间（未知账号走 dummy 哈希比对）+ 内存登录限流（5 次/60s）。 */
@Service
public class AuthService {

    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7ZBUYbI2fUL5jH9hTDN6Q8eQZbB5D7K";
    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 60_000;

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
        failures.compute(key, (k, w) -> {
            if (w == null || !w.inWindow()) {
                return new Window();
            }
            w.count.incrementAndGet();
            return w;
        });
    }

    private static final class Window {
        private final long startedAt = System.currentTimeMillis();
        private final AtomicInteger count = new AtomicInteger(1);

        boolean inWindow() {
            return System.currentTimeMillis() - startedAt < WINDOW_MS;
        }
    }
}
