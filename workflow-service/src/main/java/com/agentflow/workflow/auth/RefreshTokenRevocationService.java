package com.agentflow.workflow.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 令牌族吊销的独立事务边界。
 *
 * <p>单独成 Bean 的原因：{@code @Transactional} 必须通过 Spring 代理生效，
 * 同类内部方法自调用不会走代理、事务注解会被忽略。吊销必须在独立事务里提交，
 * 才能保证调用方（RefreshTokenService.rotate）因抛出 BusinessException 回滚时，
 * 「令牌重用即吊销全族」已经落库——这是失效防护能生效的关键（P0-2 修复）。
 */
@Service
public class RefreshTokenRevocationService {

    private final RefreshTokenMapper mapper;

    public RefreshTokenRevocationService(RefreshTokenMapper mapper) {
        this.mapper = mapper;
    }

    /** REQUIRES_NEW：挂起调用方事务，独立提交；即使调用方随后回滚也不影响吊销结果。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(String familyId) {
        mapper.revokeFamily(familyId);
    }
}
