package com.agentflow.workflow.audit;

/** 审计事件发布口（topic af-audit-event；P0 MQ 故障降级为日志，见 README 已知限制）。 */
public interface AuditPublisher {

    void publish(AuditMessage msg);
}
