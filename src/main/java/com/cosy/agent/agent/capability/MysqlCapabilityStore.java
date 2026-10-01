package com.cosy.agent.agent.capability;

import com.cosy.agent.config.VectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MySQL 能力持久化（cosy.agent.capability.store=mysql 时装配）：CP 模式提供者落库，
 * 重启后恢复。表 capability_provider / capability（复用 cosy.agent.capability 同段
 * mysql 连接参数，与 task store 的 mysql 同构）；启动 CREATE TABLE IF NOT EXISTS 自建表。
 *
 * <p>原生 JDBC（参考 {@link VectorProperties} / {@link TaskProperties} 同款），
 * 不触发 Spring DataSource 自动配置；MySQL 未安装时应用不受影响（本类不装配）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.capability", name = "store", havingValue = "mysql")
public class MysqlCapabilityStore implements CapabilityStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MysqlCapabilityStore.class);

    private final String url;
    private final String username;
    private final String password;
    private final Connection connection;

    public MysqlCapabilityStore(CapabilityProperties properties) {
        CapabilityProperties.Mysql mysql = properties.mysql();
        this.url = mysql.url();
        this.username = mysql.username();
        this.password = mysql.password();
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            this.connection = DriverManager.getConnection(url, username, password);
            createTables();
            log.info("MySQL 能力存储已连接: {}", url);
        } catch (Exception e) {
            throw new IllegalStateException("MySQL 能力存储初始化失败: " + url, e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS capability_provider (
                      provider_id   VARCHAR(64)  PRIMARY KEY,
                      app_name      VARCHAR(128) NOT NULL,
                      base_url      VARCHAR(512) NOT NULL,
                      auth_type     VARCHAR(32)  NOT NULL DEFAULT 'shared-secret',
                      call_token    VARCHAR(128),
                      mode          VARCHAR(8)   NOT NULL,
                      status        VARCHAR(16)  NOT NULL DEFAULT 'UP',
                      last_beat_at  DATETIME(3)  NULL,
                      created_at    DATETIME(3)  NOT NULL
                    ) DEFAULT CHARSET=utf8mb4""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS capability (
                      capability_name   VARCHAR(128) NOT NULL,
                      provider_id       VARCHAR(64)  NOT NULL,
                      description       VARCHAR(1024),
                      parameters_schema TEXT,
                      endpoint_path     VARCHAR(256) NOT NULL,
                      endpoint_method   VARCHAR(8)   NOT NULL,
                      retryable         BOOLEAN      NOT NULL DEFAULT FALSE,
                      namespace         VARCHAR(64)  NOT NULL DEFAULT 'default',
                      PRIMARY KEY (capability_name, provider_id),
                      CONSTRAINT fk_cap_provider FOREIGN KEY (provider_id)
                        REFERENCES capability_provider (provider_id) ON DELETE CASCADE
                    ) DEFAULT CHARSET=utf8mb4""");
        }
    }

    @Override
    public void save(CapabilityProvider provider, List<Capability> capabilities) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO capability_provider
                      (provider_id, app_name, base_url, auth_type, call_token, mode, status, last_beat_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      app_name = VALUES(app_name), base_url = VALUES(base_url),
                      auth_type = VALUES(auth_type), call_token = VALUES(call_token),
                      mode = VALUES(mode), status = VALUES(status), last_beat_at = VALUES(last_beat_at)""")) {
                ps.setString(1, provider.providerId());
                ps.setString(2, provider.appName());
                ps.setString(3, provider.baseUrl());
                ps.setString(4, provider.authType());
                ps.setString(5, provider.callToken());
                ps.setString(6, provider.mode());
                ps.setString(7, provider.status().name());
                ps.setTimestamp(8, ts(provider.lastBeatAt()));
                ps.setTimestamp(9, ts(provider.createdAt()));
                ps.executeUpdate();
            }
            for (Capability cap : capabilities) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO capability
                          (capability_name, provider_id, description, parameters_schema, endpoint_path, endpoint_method, retryable, namespace)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          description = VALUES(description), parameters_schema = VALUES(parameters_schema),
                          endpoint_path = VALUES(endpoint_path), endpoint_method = VALUES(endpoint_method),
                          retryable = VALUES(retryable), namespace = VALUES(namespace)""")) {
                    ps.setString(1, cap.fullName());
                    ps.setString(2, provider.providerId());
                    ps.setString(3, cap.description());
                    ps.setString(4, cap.parameters() == null ? "{}" : cap.parameters().toString());
                    ps.setString(5, cap.endpointPath());
                    ps.setString(6, cap.endpointMethod());
                    ps.setBoolean(7, cap.retryable());
                    ps.setString(8, cap.namespace());
                    ps.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("能力落库失败: provider=" + provider.providerId(), e);
        } finally {
            autoCommitQuietly();
        }
    }

    @Override
    public List<CapabilityProvider> loadProviders() {
        List<CapabilityProvider> list = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM capability_provider")) {
            while (rs.next()) {
                list.add(new CapabilityProvider(
                        rs.getString("provider_id"),
                        rs.getString("app_name"),
                        rs.getString("base_url"),
                        rs.getString("auth_type"),
                        rs.getString("mode"),
                        safeStatus(rs.getString("status")),
                        rs.getTimestamp("last_beat_at") == null ? null : rs.getTimestamp("last_beat_at").toInstant(),
                        rs.getString("call_token"),
                        rs.getTimestamp("created_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("加载能力提供者失败", e);
        }
        return list;
    }

    @Override
    public Map<String, List<Capability>> loadCapabilities() {
        Map<String, List<Capability>> map = new LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM capability")) {
            while (rs.next()) {
                String capName = rs.getString("capability_name");
                String full = rs.getString("parameters_schema");
                Map<String, String> parameters = parseParameters(full);
                Capability cap = new Capability(
                        stripNamespace(rs.getString("capability_name"), rs.getString("namespace")),
                        rs.getString("description"),
                        parameters,
                        rs.getString("endpoint_path"),
                        rs.getString("endpoint_method"),
                        rs.getBoolean("retryable"),
                        rs.getString("namespace"));
                map.computeIfAbsent(capName, k -> new ArrayList<>()).add(cap);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("加载能力定义失败", e);
        }
        return map;
    }

    @Override
    public void deleteProvider(String providerId) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM capability_provider WHERE provider_id = ?")) {
            ps.setString(1, providerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除能力提供者失败: " + providerId, e);
        }
    }

    @Override
    public void destroy() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // 关闭阶段忽略
        }
    }

    private Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private CapabilityStatus safeStatus(String s) {
        try {
            return CapabilityStatus.valueOf(s);
        } catch (Exception e) {
            return CapabilityStatus.UNKNOWN;
        }
    }

    private Map<String, String> parseParameters(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return map;
        }
        // 存储格式为 Map.toString()：{orderId=string, amount=number}
        String body = raw.trim();
        if (body.startsWith("{") && body.endsWith("}")) {
            body = body.substring(1, body.length() - 1);
        }
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private String stripNamespace(String fullName, String namespace) {
        if (namespace != null && !namespace.isBlank() && fullName.startsWith(namespace + "_")) {
            return fullName.substring(namespace.length() + 1);
        }
        return fullName;
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 忽略回滚失败
        }
    }

    private void autoCommitQuietly() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 忽略
        }
    }
}
