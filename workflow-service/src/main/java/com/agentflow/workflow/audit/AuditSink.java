package com.agentflow.workflow.audit;

/** 审计投递出口。实现方决定「发到 MQ」还是「直写审计表」，对 relay 无差别。 */
public interface AuditSink {

    /** 投递一条审计事件；抛异常表示本次未成功，由 relay 退避重投。 */
    void deliver(AuditMessage msg) throws Exception;

    /** 供运维日志/管理端展示的出口名。 */
    String name();
}
