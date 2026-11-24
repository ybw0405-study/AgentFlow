package com.agentflow.connector.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** tool_connector / tool_credential 读写（JdbcTemplate；表结构见 V005）。 */
@Repository
public class ConnectorRepository {

    private static final RowMapper<ConnectorRow> CONNECTOR = ConnectorRepository::mapConnector;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(ConnectorRepository.class);

    private final JdbcTemplate jdbc;

    public ConnectorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ConnectorRow(String id, String tenantId, String name, String type,
                               String authType, JsonNode config, boolean enabled,
                               String createdBy) {
    }

    public record CredentialRow(String connectorId, String tenantId, byte[] secret,
                                byte[] oauthToken, String keyId, Timestamp expiresAt,
                                String scope) {
    }

    private static ConnectorRow mapConnector(ResultSet rs, int rowNum) throws SQLException {
        JsonNode config;
        try {
            String raw = rs.getString("config_json");
            config = raw == null ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
        } catch (Exception e) {
            // 脏 config_json 静默变空配置会把排查方向引向"没配"，至少留一条告警（C-13）。
            // 仍返回空对象以免一条脏数据让整个列表接口 500。
            // 绝不把原文打进日志：config_json 含 headers / auth.headerName，可能携带
            // 静态 API key / token。只留 id + 异常类型 + 长度供排查。
            String raw = rs.getString("config_json");
            log.warn("invalid config_json for connector {}; treating as empty (type={}, len={})",
                    rs.getString("id"), e.getClass().getSimpleName(),
                    raw == null ? 0 : raw.length());
            config = MAPPER.createObjectNode();
        }
        return new ConnectorRow(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("name"), rs.getString("type"), rs.getString("auth_type"),
                config, rs.getBoolean("enabled"), rs.getString("created_by"));
    }

    public List<ConnectorRow> listByTenants(Collection<String> tenantIds) {
        if (tenantIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", tenantIds.stream().map(t -> "?").toList());
        return jdbc.query("SELECT * FROM tool_connector WHERE deleted_at IS NULL"
                        + " AND tenant_id IN (" + placeholders + ") ORDER BY created_at",
                CONNECTOR, tenantIds.toArray());
    }

    public Optional<ConnectorRow> findById(String id) {
        return jdbc.query("SELECT * FROM tool_connector WHERE id = ? AND deleted_at IS NULL",
                CONNECTOR, id).stream().findFirst();
    }

    public Optional<ConnectorRow> findByTenantAndName(String tenantId, String name) {
        return jdbc.query("SELECT * FROM tool_connector WHERE tenant_id = ? AND name = ?"
                        + " AND deleted_at IS NULL", CONNECTOR, tenantId, name)
                .stream().findFirst();
    }

    public void insert(String id, String tenantId, String name, String type, String authType,
                       String configJson, String createdBy) {
        jdbc.update("INSERT INTO tool_connector (id, tenant_id, name, type, auth_type,"
                        + " config_json, enabled, created_by) VALUES (?,?,?,?,?,?,1,?)",
                id, tenantId, name, type, authType, configJson, createdBy);
    }

    public int updateConfig(String id, String tenantId, String name, String authType,
                            String configJson, boolean enabled) {
        return jdbc.update("UPDATE tool_connector SET name = ?, auth_type = ?,"
                        + " config_json = ?, enabled = ? WHERE id = ? AND tenant_id = ?"
                        + " AND deleted_at IS NULL",
                name, authType, configJson, enabled, id, tenantId);
    }

    public int softDelete(String id, String tenantId) {
        return jdbc.update("UPDATE tool_connector SET deleted_at = NOW(3)"
                + " WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL", id, tenantId);
    }

    public void upsertCredential(String id, String connectorId, String tenantId, byte[] secret,
                                 byte[] oauthToken, String keyId, Timestamp expiresAt,
                                 String scope) {
        // 单条 UPSERT 替代 SELECT-then-INSERT：并发 authorize + OAuth callback 不再
        // 双双通过存在性检查后撞唯一键 uq_tcr_conn(connector_id) 抛 500（C-14）。
        jdbc.update("INSERT INTO tool_credential (id, connector_id, tenant_id,"
                        + " encrypted_secret, oauth_token_enc, key_id, expires_at, scope)"
                        + " VALUES (?,?,?,?,?,?,?,?)"
                        + " ON DUPLICATE KEY UPDATE encrypted_secret = VALUES(encrypted_secret),"
                        + " oauth_token_enc = VALUES(oauth_token_enc), key_id = VALUES(key_id),"
                        + " expires_at = VALUES(expires_at), scope = VALUES(scope),"
                        + " updated_at = NOW(3)",
                id, connectorId, tenantId, secret, oauthToken, keyId, expiresAt, scope);
    }

    public Optional<CredentialRow> findCredential(String connectorId) {
        return jdbc.query("SELECT * FROM tool_credential WHERE connector_id = ?",
                (rs, i) -> new CredentialRow(rs.getString("connector_id"),
                        rs.getString("tenant_id"), rs.getBytes("encrypted_secret"),
                        rs.getBytes("oauth_token_enc"), rs.getString("key_id"),
                        rs.getTimestamp("expires_at"), rs.getString("scope")),
                connectorId).stream().findFirst();
    }

    public List<String> listTenantIds() {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM tenant WHERE deleted_at IS NULL", String.class));
    }

}
