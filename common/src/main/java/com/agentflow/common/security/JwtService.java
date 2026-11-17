package com.agentflow.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * JWT 无状态认证（HS256）。claim：sub=userId, iss, aud, iat, exp, jti, roles。
 * 密钥 ≥32 字节，经环境变量注入。
 */
public class JwtService {

    public static final String ISSUER = "agentflow";
    public static final String AUDIENCE = "agentflow-api";

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(String secret, long ttlMillis) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("jwt secret must be >= 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = ttlMillis;
    }

    public String issue(String userId, List<String> roles) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .claim("roles", roles)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 验签 + iss/aud/过期校验；失败抛 io.jsonwebtoken.JwtException。 */
    @SuppressWarnings("unchecked")
    public AuthenticatedUser verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Object roles = claims.get("roles");
        List<String> roleList = roles instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.<String>of();
        return new AuthenticatedUser(claims.getSubject(), roleList);
    }
}
