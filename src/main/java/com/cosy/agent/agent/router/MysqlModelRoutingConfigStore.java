package com.cosy.agent.agent.router;

import com.cosy.agent.config.ModelRoutingProperties;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MySQL 模型路由配置持久化（cosy.agent.model-routing.store=mysql 时装配）：
 * 表 model_platform（平台：base-url / api-key[加密] / type / enabled / timeout_ms）、
 * model_spec（平台下模型规格：context-window / capabilities）、model_route（路由类型 → 有序候选）。
 * api-key 经 {@link ApiKeyCipher} 加密落库、读取解密；旧表自动补列兼容。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.model-routing", name = "store", havingValue = "mysql")
public class MysqlModelRoutingConfigStore implements ModelRoutingConfigStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MysqlModelRoutingConfigStore.class);

    private final String url;
    private final String username;
    private final String password;
    private final ApiKeyCipher cipher;

    public MysqlModelRoutingConfigStore(ModelRoutingProperties properties, ApiKeyCipher cipher) {
        var mysql = properties.mysql();
        this.url = mysql.url();
        this.username = mysql.username();
        this.password = mysql.password();
        this.cipher = cipher;
        initSchema();
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private void initSchema() {
        try (Connection conn = open()) {
            conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS model_platform (
                        platform_name    VARCHAR(64)  PRIMARY KEY,
                        base_url         VARCHAR(512) NOT NULL,
                        api_key          VARCHAR(512),
                        type             VARCHAR(32)  DEFAULT 'openai',
                        enabled          TINYINT      DEFAULT 1,
                        timeout_ms       INT          DEFAULT 60000,
                        completions_path VARCHAR(128) DEFAULT '/v1/chat/completions'
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS model_spec (
                        platform_name  VARCHAR(64)   NOT NULL,
                        model_id       VARCHAR(128)  NOT NULL,
                        context_window INT           DEFAULT 0,
                        capabilities   VARCHAR(1024) DEFAULT '',
                        PRIMARY KEY (platform_name, model_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS model_route (
                        route_type  VARCHAR(64)  NOT NULL,
                        candidate   VARCHAR(256) NOT NULL,
                        seq         INT          NOT NULL,
                        PRIMARY KEY (route_type, candidate)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            // 旧表补列：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，先按 information_schema 判断再 ALTER（幂等）
            ensureColumn(conn, "model_platform", "type", "VARCHAR(32) DEFAULT 'openai'");
            ensureColumn(conn, "model_platform", "enabled", "TINYINT DEFAULT 1");
            ensureColumn(conn, "model_platform", "timeout_ms", "INT DEFAULT 60000");
            ensureColumn(conn, "model_platform", "completions_path",
                    "VARCHAR(128) DEFAULT '/v1/chat/completions'");
            conn.createStatement().execute("ALTER TABLE model_platform MODIFY COLUMN api_key VARCHAR(512)");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化模型路由配置表失败（store=mysql）: " + e.getMessage(), e);
        }
    }

    /** information_schema 幂等补列（列不存在才 ALTER；并发/异常静默） */
    private void ensureColumn(Connection conn, String table, String column, String ddl)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    conn.createStatement().execute(
                            "ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
                }
            }
        } catch (SQLException ignored) {
            // 并发建列/权限等异常静默（后续 load 可见列即恢复）
        }
    }

    @Override
    public Optional<RouteConfig> load() {
        try (Connection conn = open()) {
            Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT platform_name, base_url, api_key, type, enabled, timeout_ms, "
                                 + "completions_path FROM model_platform ORDER BY platform_name")) {
                while (rs.next()) {
                    String name = rs.getString("platform_name");
                    platforms.put(name, new RouteConfig.ModelPlatform(
                            name, rs.getString("base_url"),
                            cipher.decrypt(rs.getString("api_key")),
                            rs.getString("completions_path"),
                            rs.getString("type"),
                            rs.getInt("enabled") != 0,
                            rs.getInt("timeout_ms"),
                            loadModels(conn, name)));
                }
            }
            Map<String, List<String>> routes = new LinkedHashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT route_type, candidate FROM model_route ORDER BY route_type, seq")) {
                while (rs.next()) {
                    routes.computeIfAbsent(rs.getString("route_type"), k -> new ArrayList<>())
                            .add(rs.getString("candidate"));
                }
            }
            if (platforms.isEmpty() && routes.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new RouteConfig(true, 5, platforms, routes));
        } catch (SQLException e) {
            log.warn("读取模型路由配置失败（回退 YAML 基线）: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private List<RouteConfig.ModelSpec> loadModels(Connection conn, String platformName) throws SQLException {
        List<RouteConfig.ModelSpec> models = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT model_id, context_window, capabilities FROM model_spec WHERE platform_name = ? ORDER BY model_id")) {
            ps.setString(1, platformName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String caps = rs.getString("capabilities");
                    List<String> capList = (caps == null || caps.isBlank())
                            ? List.of() : List.of(caps.split(","));
                    models.add(new RouteConfig.ModelSpec(rs.getString("model_id"),
                            rs.getInt("context_window"), capList));
                }
            }
        }
        return models;
    }

    @Override
    public void save(RouteConfig config) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM model_route");
                    st.executeUpdate("DELETE FROM model_spec");
                    st.executeUpdate("DELETE FROM model_platform");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO model_platform (platform_name, base_url, api_key, type, enabled, timeout_ms, completions_path) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    config.platforms().forEach((name, pf) -> {
                        try {
                            ps.setString(1, name);
                            ps.setString(2, pf.baseUrl());
                            ps.setString(3, cipher.encrypt(pf.apiKey()));
                            ps.setString(4, pf.type());
                            ps.setInt(5, pf.enabled() ? 1 : 0);
                            ps.setInt(6, pf.timeoutMs());
                            ps.setString(7, pf.completionsPath());
                            ps.executeUpdate();
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO model_spec (platform_name, model_id, context_window, capabilities) VALUES (?, ?, ?, ?)")) {
                    config.platforms().forEach((name, pf) -> pf.models().forEach(m -> {
                        try {
                            ps.setString(1, name);
                            ps.setString(2, m.modelId());
                            ps.setInt(3, m.contextWindow());
                            ps.setString(4, String.join(",", m.capabilities()));
                            ps.executeUpdate();
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }));
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO model_route (route_type, candidate, seq) VALUES (?, ?, ?)")) {
                    config.routes().forEach((type, candidates) -> {
                        for (int i = 0; i < candidates.size(); i++) {
                            try {
                                ps.setString(1, type);
                                ps.setString(2, candidates.get(i));
                                ps.setInt(3, i);
                                ps.executeUpdate();
                            } catch (SQLException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存模型路由配置失败（store=mysql）: " + e.getMessage(), e);
        }
    }

    @Override
    public void destroy() {
        // 无共享连接，无需释放
    }
}
