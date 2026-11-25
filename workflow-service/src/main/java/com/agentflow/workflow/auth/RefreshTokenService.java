package com.agentflow.workflow.auth;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 刷新令牌轮换族（复用 DocQA 语义）：
 * 重用检测——拿 USED/REVOKED 令牌来轮换即吊销全族；consumeActive 原子消费必须恰好 1 行。
 * 库中只存 SHA-256 哈希。
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenMapper mapper;
    private final RefreshTokenRevocationService revocationService;
    private final long refreshTtlDays;

    public RefreshTokenService(RefreshTokenMapper mapper,
                               RefreshTokenRevocationService revocationService,
                               @Value("${agentflow.jwt.refresh-ttl-days}") long refreshTtlDays) {
        this.mapper = mapper;
        this.revocationService = revocationService;
        this.refreshTtlDays = refreshTtlDays;
    }

    public record IssuedToken(String rawToken, String userId) {
    }

    public IssuedToken startFamily(String userId) {
        return issue(userId, UUID.randomUUID().toString());
    }

    @Transactional
    public IssuedToken rotate(String rawToken) {
        var row = mapper.findByHash(sha256(rawToken));
        if (row == null) {
            throw new BusinessException(ErrorCode.USR_TOKEN_001, "refresh token invalid");
        }
        if (!"ACTIVE".equals(row.status())) {
            // 重用检测：整个族吊销。必须在独立事务提交，否则紧接着抛出的
            // BusinessException(RuntimeException) 会让本方法事务回滚、吊销失效（P0-2 修复）。
            revocationService.revokeFamily(row.familyId());
            throw new BusinessException(ErrorCode.USR_TOKEN_001, "refresh token reuse detected");
        }
        if (row.expiresAt().toInstant().isBefore(Instant.now())) {
            revocationService.revokeFamily(row.familyId());
            throw new BusinessException(ErrorCode.USR_TOKEN_001, "refresh token expired");
        }
        if (mapper.consumeActive(row.id()) != 1) {
            revocationService.revokeFamily(row.familyId());
            throw new BusinessException(ErrorCode.USR_TOKEN_001, "refresh token conflict");
        }
        return issue(row.userId(), row.familyId());
    }

    public void revoke(String rawToken) {
        var row = mapper.findByHash(sha256(rawToken));
        if (row != null) {
            mapper.revokeFamily(row.familyId());
        }
    }

    private IssuedToken issue(String userId, String familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        mapper.insert(UUID.randomUUID().toString(), userId, sha256(raw), familyId,
                Timestamp.from(Instant.now().plus(refreshTtlDays, ChronoUnit.DAYS)));
        return new IssuedToken(raw, userId);
    }

    static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
