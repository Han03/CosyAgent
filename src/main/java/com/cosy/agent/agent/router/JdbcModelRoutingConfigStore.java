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
 * PostgreSQL 模型路由配置持久化（cosy.agent.model-routing.store=pg 时装配）：
 * 表 model_platform（平台：base-url / api-key）+ model_route（路由类型 → 有序候选），
 * 启动自建表；原生 JDBC（连接参数复用 cosy.agent.model-routing.pg.*，与 task store 同库同源）。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.model-routing", name = "store", havingValue = "pg")
public class JdbcModelRoutingConfigStore implements ModelRoutingConfigStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JdbcModelRoutingConfigStore.class);

    private final String url;
    private final String username;
    private final String password;

    public JdbcModelRoutingConfigStore(ModelRoutingProperties properties) {
        var pg = properties.pg();
        this.url = pg.url();
        this.username = pg.username();
        this.password = pg.password();
        initSchema();
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private void initSchema() {
        try (Connection conn = open()) {
            conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS model_platform (
                        platform_name VARCHAR(64) PRIMARY KEY,
                        base_url      VARCHAR(512) NOT NULL,
                        api_key       VARCHAR(256)
                    )""");
            conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS model_route (
                        route_type  VARCHAR(64)  NOT NULL,
                        candidate   VARCHAR(256) NOT NULL,
                        seq         INT          NOT NULL,
                        PRIMARY KEY (route_type, candidate)
                    )""");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化模型路由配置表失败（store=pg）: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<RouteConfig> load() {
        try (Connection conn = open()) {
            Map<String, RouteConfig.ModelPlatform> platforms = new LinkedHashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT platform_name, base_url, api_key FROM model_platform ORDER BY platform_name")) {
                while (rs.next()) {
                    String name = rs.getString("platform_name");
                    platforms.put(name, new RouteConfig.ModelPlatform(
                            name, rs.getString("base_url"), rs.getString("api_key"), null));
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

    @Override
    public void save(RouteConfig config) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM model_route");
                    st.executeUpdate("DELETE FROM model_platform");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO model_platform (platform_name, base_url, api_key) VALUES (?, ?, ?)")) {
                    config.platforms().forEach((name, pf) -> {
                        try {
                            ps.setString(1, name);
                            ps.setString(2, pf.baseUrl());
                            ps.setString(3, pf.apiKey());
                            ps.executeUpdate();
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    });
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
            throw new IllegalStateException("保存模型路由配置失败（store=pg）: " + e.getMessage(), e);
        }
    }

    @Override
    public void destroy() {
        // 无共享连接，无需释放
    }
}
