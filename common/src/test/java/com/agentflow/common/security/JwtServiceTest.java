package com.agentflow.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "agentflow-test-secret-key-0123456789ab";
    private final JwtService service = new JwtService(SECRET, 900_000);

    @Test
    void issueAndVerifyRoundtrip() {
        String token = service.issue("user-1", List.of("USER", "ADMIN"));
        AuthenticatedUser user = service.verify(token);
        assertEquals("user-1", user.userId());
        assertEquals(List.of("USER", "ADMIN"), user.roles());
        assertTrue(user.isAdmin());
    }

    @Test
    void tamperedTokenRejected() {
        String token = service.issue("user-1", List.of("USER"));
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(JwtException.class, () -> service.verify(tampered));
    }

    @Test
    void expiredTokenRejected() {
        JwtService shortLived = new JwtService(SECRET, -1000);
        String token = shortLived.issue("user-1", List.of("USER"));
        assertThrows(ExpiredJwtException.class, () -> shortLived.verify(token));
    }

    @Test
    void wrongSecretRejected() {
        JwtService other = new JwtService("another-test-secret-key-0123456789ab", 900_000);
        String token = other.issue("user-1", List.of("USER"));
        assertThrows(JwtException.class, () -> service.verify(token));
    }

    @Test
    void shortSecretRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService("short", 1000));
    }
}
