package com.agentflow.workflow.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.workflow.auth.RefreshTokenMapper.RefreshTokenRow;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P0-2 回归：refresh token 重用检测必须在抛异常前，把「吊销全族」委托给独立事务 Bean。
 *
 * <p>用 Mockito 验证委托关系——只要 rotate() 调用的是 revocationService（REQUIRES_NEW Bean）
 * 而非直接调 mapper.revokeFamily，吊销就不会被本方法事务回滚。真实事务语义由
 * Spring 的 REQUIRES_NEW 保证，这里锁定「委托给独立 Bean」这个可验证的不变量。
 */
class RefreshTokenServiceTest {

    private RefreshTokenMapper mapper;
    private RefreshTokenRevocationService revocationService;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        mapper = mock(RefreshTokenMapper.class);
        revocationService = mock(RefreshTokenRevocationService.class);
        service = new RefreshTokenService(mapper, revocationService, 7L);
    }

    private static RefreshTokenRow row(String status) {
        return new RefreshTokenRow("id-1", "user-1", "family-1", status,
                Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    @Test
    void reuseOfUsedTokenRevokesFamilyViaIndependentBean() {
        String raw = "some-raw-token";
        when(mapper.findByHash(anyString())).thenReturn(row("USED"));

        assertThrows(BusinessException.class, () -> service.rotate(raw));

        // 关键：吊销必须走独立事务 Bean，而不是本方法事务里的 mapper 直接调用
        verify(revocationService).revokeFamily("family-1");
        verify(mapper, never()).revokeFamily(anyString());
    }

    @Test
    void expireAndConflictAlsoRevokeViaIndependentBean() {
        String raw = "expired-token";
        var expired = new RefreshTokenRow("id-2", "user-2", "family-2", "ACTIVE",
                Timestamp.from(Instant.now().minusSeconds(10)));
        when(mapper.findByHash(anyString())).thenReturn(expired);

        assertThrows(BusinessException.class, () -> service.rotate(raw));

        verify(revocationService).revokeFamily("family-2");
        verify(mapper, never()).revokeFamily(anyString());
    }
}
