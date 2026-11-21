package com.agentflow.workflow.audit;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计 outbox 的领取器。
 *
 * <p>把「捞取 + 领取」收进一个短事务：due() 的 FOR UPDATE SKIP LOCKED 在事务内真正
 * 持有行锁，claim() 紧接着把行标为 PROCESSING 并提交。之后其它副本的 due() 只会
 * 看到 PENDING，本批由当前副本独占，网络投递留在事务外，避免长事务。
 *
 * <p>单独成 Bean 而非 relay() 内部方法：Spring 的 @Transactional 走代理，同类
 * 自调用会绕过事务，必须经外部 Bean 调用才生效。
 */
@Component
public class OutboxClaimer {

    private final AuditOutboxMapper outbox;

    public OutboxClaimer(AuditOutboxMapper outbox) {
        this.outbox = outbox;
    }

    /** 短事务：捞取 + 领取。返回的行由本副本独占投递。 */
    @Transactional
    public List<AuditOutboxMapper.OutboxRow> claim(int limit) {
        List<AuditOutboxMapper.OutboxRow> rows = outbox.due(limit);
        for (AuditOutboxMapper.OutboxRow row : rows) {
            outbox.claim(row.id());
        }
        return rows;
    }
}
