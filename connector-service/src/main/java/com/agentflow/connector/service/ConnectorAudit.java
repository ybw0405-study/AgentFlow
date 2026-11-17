package com.agentflow.connector.service;

import com.agentflow.common.tenant.TenantContext.TenantInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 连接器变更审计：直接写 audit_event（只追加表）。
 * payload 只放标识与类型，绝不含密钥；写入失败不阻断业务（降级为日志）。
 */
@Component
public class ConnectorAudit {

    private static final Logger log = LoggerFactory.getLogger(ConnectorAudit.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ConnectorAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(TenantInfo tenant, String traceId, String eventType,
                       Map<String, Object> payload) {
        try {
            jdbc.update("INSERT INTO audit_event (id, tenant_id, trace_id, actor_id,"
                            + " event_type, payload_redacted) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), tenant.tenantId(),
                    traceId == null ? "-" : traceId, tenant.userId(), eventType,
                    JSON.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("connector audit write failed: {}", e.toString());
        }
    }
}
