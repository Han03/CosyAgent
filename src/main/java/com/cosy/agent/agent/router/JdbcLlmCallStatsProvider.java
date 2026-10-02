package com.cosy.agent.agent.router;

import com.cosy.agent.config.ModelRoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * LLM 调用统计的 MySQL 实现：查询 llm_call_log 近 7 天按 chosen_model 聚合。
 *
 * <p>复用 cosy.agent.model-routing.mysql 连接段（与路由 store 同库同源）。
 * 表 llm_call_log 由「大模型调用记录方案」实施时自建；在本实现就绪但表尚不存在时
 * 查询失败仅返回空 map（记 debug），L2 可靠性取中性值，不影响路由可用性。
 * 连接初始化失败时本实现降级为"无数据"（不阻断应用启动）。</p>
 */
@Component
public class JdbcLlmCallStatsProvider implements LlmCallStatsProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcLlmCallStatsProvider.class);

    private final Connection connection; // null = 不可用（无数据）

    public JdbcLlmCallStatsProvider(ModelRoutingProperties properties) {
        Connection conn = null;
        try {
            ModelRoutingProperties.Mysql mysql = properties.mysql();
            Class.forName("com.mysql.cj.jdbc.Driver");
            conn = DriverManager.getConnection(mysql.url(), mysql.username(), mysql.password());
            log.info("LLM 调用统计数据源已连接: {}", mysql.url());
        } catch (Exception e) {
            log.warn("LLM 调用统计数据源不可用（自动路由 L2 降级为中性值）: {}", e.getMessage());
        }
        this.connection = conn;
    }

    @Override
    public Map<String, LlmCallStatsProvider.ModelStats> statsLast7Days() {
        if (connection == null) {
            return Map.of();
        }
        Map<String, LlmCallStatsProvider.ModelStats> result = new HashMap<>();
        String sql = """
                SELECT chosen_model,
                       COUNT(*) AS calls,
                       SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success,
                       SUM(CASE WHEN status = 'SUCCESS' AND reasons IS NOT NULL AND reasons <> ''
                                THEN 1 ELSE 0 END) AS degraded
                FROM llm_call_log
                WHERE started_at >= ? AND chosen_model IS NOT NULL AND chosen_model <> ''
                GROUP BY chosen_model""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(Duration.ofDays(7))));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long calls = rs.getLong("calls");
                    long success = rs.getLong("success");
                    long degraded = rs.getLong("degraded");
                    result.put(rs.getString("chosen_model"), new LlmCallStatsProvider.ModelStats(
                            calls,
                            calls == 0 ? 0 : (double) success / calls,
                            calls == 0 ? 0 : (double) degraded / calls));
                }
            }
        } catch (SQLException e) {
            // llm_call_log 尚未建立（调用记录方案未实施）或查询异常：按无数据处理
            log.debug("LLM 调用统计查询不可用（llm_call_log 不存在或异常）: {}", e.getMessage());
            return Map.of();
        }
        return result;
    }
}
