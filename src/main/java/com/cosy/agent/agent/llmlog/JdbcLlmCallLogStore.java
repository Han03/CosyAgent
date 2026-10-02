package com.cosy.agent.agent.llmlog;

import com.cosy.agent.config.LlmCallLogProperties;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MySQL 大模型调用记录存储（cosy.agent.llmlog.enabled=true 时装配）：
 * 启动自建 llm_call_log 表（开发阶段内容明文 MEDIUMTEXT），
 * 批量写入 / 分页查询 / 聚合统计。原生 JDBC（与路由/能力 store 同款），
 * MySQL 不可用时装配失败即功能不可用（不影响应用主体启动）。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.llmlog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JdbcLlmCallLogStore implements LlmCallLogStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JdbcLlmCallLogStore.class);

    private final Connection connection;

    public JdbcLlmCallLogStore(LlmCallLogProperties properties) {
        LlmCallLogProperties.Mysql mysql = properties.mysql();
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            this.connection = DriverManager.getConnection(mysql.url(), mysql.username(), mysql.password());
            createTables();
            log.info("LLM 调用记录存储已连接: {}", mysql.url());
        } catch (Exception e) {
            throw new IllegalStateException("LLM 调用记录存储初始化失败: " + mysql.url(), e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS llm_call_log (
                      id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
                      trace_id           VARCHAR(64)  NOT NULL,
                      session_id         VARCHAR(64)  NULL,
                      task_id            VARCHAR(64)  NULL,
                      iteration          INT          NOT NULL DEFAULT 0,
                      tool_name          VARCHAR(64)  NULL,
                      route_type         VARCHAR(32)  NOT NULL DEFAULT 'default',
                      model_choice       VARCHAR(128) NOT NULL,
                      candidate_chain    VARCHAR(512) NULL,
                      injected_tools     VARCHAR(512) NULL,
                      attempts           VARCHAR(512) NULL,
                      reasons            TEXT         NULL,
                      chosen_model       VARCHAR(128) NULL,
                      status             VARCHAR(16)  NOT NULL,
                      error_msg          VARCHAR(512) NULL,
                      decision_rationale VARCHAR(1024) NULL,
                      prompt_content     MEDIUMTEXT   NULL,
                      raw_prompt         MEDIUMTEXT   NULL,
                      response_content   MEDIUMTEXT   NULL,
                      prompt_tokens      INT          NULL,
                      completion_tokens  INT          NULL,
                      total_tokens       INT          NULL,
                      latency_ms         INT          NOT NULL DEFAULT 0,
                      started_at         DATETIME(3)  NOT NULL,
                      finished_at        DATETIME(3)  NOT NULL,
                      KEY idx_session (session_id, started_at),
                      KEY idx_model   (chosen_model, started_at),
                      KEY idx_status  (status, started_at),
                      KEY idx_trace   (trace_id)
                    ) DEFAULT CHARSET=utf8mb4""");
            // 老表补列（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，information_schema 判断后幂等 ALTER）
            ensureColumn(st, "injected_tools", "VARCHAR(512) NULL");
            ensureColumn(st, "raw_prompt", "MEDIUMTEXT NULL");
        }
    }

    /** information_schema 幂等补列（列不存在才 ALTER；并发/异常静默） */
    private void ensureColumn(Statement st, String column, String ddl) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'llm_call_log' AND COLUMN_NAME = ?")) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    st.executeUpdate("ALTER TABLE llm_call_log ADD COLUMN " + column + " " + ddl);
                }
            }
        } catch (SQLException ignored) {
            // 并发建列等异常静默（后续写入可见列即恢复）
        }
    }

    // ---- 写入 ----

    @Override
    public void saveAll(List<LlmCallLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO llm_call_log
                  (trace_id, session_id, task_id, iteration, tool_name, route_type, model_choice,
                   candidate_chain, injected_tools, attempts, reasons, chosen_model, status, error_msg, decision_rationale,
                   prompt_content, raw_prompt, response_content, prompt_tokens, completion_tokens, total_tokens,
                   latency_ms, started_at, finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (LlmCallLog logEntry : logs) {
                ps.setString(1, logEntry.traceId());
                ps.setString(2, logEntry.sessionId());
                ps.setString(3, logEntry.taskId());
                ps.setInt(4, logEntry.iteration());
                ps.setString(5, logEntry.toolName());
                ps.setString(6, logEntry.routeType());
                ps.setString(7, logEntry.modelChoice());
                ps.setString(8, truncate(logEntry.candidateChain(), 512));
                ps.setString(9, truncate(logEntry.injectedTools(), 512));
                ps.setString(10, truncate(logEntry.attempts(), 512));
                ps.setString(11, truncate(logEntry.reasons(), 1000));
                ps.setString(12, logEntry.chosenModel());
                ps.setString(13, logEntry.status());
                ps.setString(14, truncate(logEntry.errorMsg(), 512));
                ps.setString(15, truncate(logEntry.decisionRationale(), 1024));
                ps.setString(16, logEntry.promptContent());
                ps.setString(17, logEntry.rawPrompt());
                ps.setString(18, logEntry.responseContent());
                ps.setObject(19, logEntry.promptTokens());
                ps.setObject(20, logEntry.completionTokens());
                ps.setObject(21, logEntry.totalTokens());
                ps.setInt(22, (int) Math.min(Integer.MAX_VALUE, logEntry.latencyMs()));
                ps.setTimestamp(23, Timestamp.from(logEntry.startedAt()));
                ps.setTimestamp(24, Timestamp.from(logEntry.finishedAt()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("LLM 调用记录批量落库失败（丢弃本次 {} 条）: {}", logs.size(), e.getMessage());
        }
    }

    // ---- 读取 ----

    @Override
    public PageResult page(int page, int size, String sessionId, String model,
                           String routeType, String status, Instant start, Instant end) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            where.add("session_id = ?");
            params.add(sessionId);
        }
        if (model != null && !model.isBlank()) {
            where.add("chosen_model = ?");
            params.add(model);
        }
        if (routeType != null && !routeType.isBlank()) {
            where.add("route_type = ?");
            params.add(routeType);
        }
        if (status != null && !status.isBlank()) {
            where.add("status = ?");
            params.add(status);
        }
        if (start != null) {
            where.add("started_at >= ?");
            params.add(Timestamp.from(start));
        }
        if (end != null) {
            where.add("started_at <= ?");
            params.add(Timestamp.from(end));
        }
        String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);

        long total = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM llm_call_log" + whereSql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    total = rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.warn("LLM 调用记录查询失败: {}", e.getMessage());
            return new PageResult(0, List.of());
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String sql = "SELECT * FROM llm_call_log" + whereSql
                + " ORDER BY started_at DESC LIMIT ? OFFSET ?";
        List<LlmCallLog> items = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = bind(ps, params);
            ps.setInt(idx, safeSize);
            ps.setInt(idx + 1, safePage * safeSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("LLM 调用记录分页查询失败: {}", e.getMessage());
        }
        return new PageResult(total, items);
    }

    @Override
    public Optional<LlmCallLog> findById(long id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM llm_call_log WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("LLM 调用记录详情查询失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public Map<String, com.cosy.agent.agent.router.LlmCallStatsProvider.ModelStats> statsLast7Days() {
        Map<String, com.cosy.agent.agent.router.LlmCallStatsProvider.ModelStats> result = new HashMap<>();
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
                    result.put(rs.getString("chosen_model"),
                            new com.cosy.agent.agent.router.LlmCallStatsProvider.ModelStats(
                                    calls,
                                    calls == 0 ? 0 : (double) success / calls,
                                    calls == 0 ? 0 : (double) degraded / calls));
                }
            }
        } catch (SQLException e) {
            log.debug("LLM 调用统计不可用: {}", e.getMessage());
            return Map.of();
        }
        return result;
    }

    /** 聚合统计（groupBy 明细拉取，Java 计算 avg/max/p95/tokens） */
    @Override
    public List<Map<String, Object>> aggregate(String groupBy, Instant start, Instant end,
                                               String sessionId, String model,
                                               String routeType, String status) {
        String col = switch (groupBy == null ? "day" : groupBy) {
            case "model" -> "chosen_model";
            case "routeType" -> "route_type";
            case "status" -> "status";
            default -> "DATE(started_at)";
        };
        String sql = "SELECT " + col + " AS g, latency_ms, status, reasons, total_tokens, "
                + "prompt_tokens, completion_tokens FROM llm_call_log WHERE started_at >= ? AND started_at <= ?";
        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(start));
        params.add(Timestamp.from(end));
        List<String> extra = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            extra.add("session_id = ?");
            params.add(sessionId);
        }
        if (model != null && !model.isBlank()) {
            extra.add("chosen_model = ?");
            params.add(model);
        }
        if (routeType != null && !routeType.isBlank()) {
            extra.add("route_type = ?");
            params.add(routeType);
        }
        if (status != null && !status.isBlank()) {
            extra.add("status = ?");
            params.add(status);
        }
        if (!extra.isEmpty()) {
            sql += " AND " + String.join(" AND ", extra);
        }
        sql += " ORDER BY g, latency_ms";

        Map<String, List<long[]>> byGroup = new LinkedHashMap<>(); // g → [latency, statusOk, degraded, totalTokens]
        Map<String, long[]> tokens = new HashMap<>(); // g → [prompt, completion, total]
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = 1;
            for (Object p : params) {
                ps.setObject(idx++, p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String g = rs.getString("g") == null ? "(未知)" : rs.getString("g");
                    int latency = rs.getInt("latency_ms");
                    boolean ok = "SUCCESS".equals(rs.getString("status"));
                    boolean degraded = ok && rs.getString("reasons") != null
                            && !rs.getString("reasons").isBlank();
                    int total = rs.getInt("total_tokens");
                    byGroup.computeIfAbsent(g, k -> new ArrayList<>())
                            .add(new long[]{latency, ok ? 1 : 0, degraded ? 1 : 0, total});
                    tokens.computeIfAbsent(g, k -> new long[3]);
                    tokens.get(g)[0] += rs.getInt("prompt_tokens");
                    tokens.get(g)[1] += rs.getInt("completion_tokens");
                    tokens.get(g)[2] += total;
                }
            }
        } catch (SQLException e) {
            log.warn("LLM 调用记录统计查询失败: {}", e.getMessage());
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        byGroup.forEach((g, rows) -> {
            rows.sort((a, b) -> Long.compare(a[0], b[0]));
            long calls = rows.size();
            long success = rows.stream().filter(r -> r[1] == 1).count();
            long degraded = rows.stream().filter(r -> r[2] == 1).count();
            long avg = (long) rows.stream().mapToLong(r -> r[0]).average().orElse(0);
            long max = rows.stream().mapToLong(r -> r[0]).max().orElse(0);
            long p95 = rows.get((int) Math.min(rows.size() - 1, Math.ceil(0.95 * rows.size()) - 1))[0];
            long[] t = tokens.get(g);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("group", g);
            item.put("calls", calls);
            item.put("successRate", calls == 0 ? 0 : Math.round(1000.0 * success / calls) / 10.0);
            item.put("degraded", degraded);
            item.put("avgLatencyMs", avg);
            item.put("maxLatencyMs", max);
            item.put("p95LatencyMs", p95);
            item.put("promptTokens", t[0]);
            item.put("completionTokens", t[1]);
            item.put("totalTokens", t[2]);
            result.add(item);
        });
        return result;
    }

    // ---- 内部 ----

    private LlmCallLog mapRow(ResultSet rs) throws SQLException {
        int pt = rs.getInt("prompt_tokens");
        Integer promptTokens = rs.wasNull() ? null : pt;
        int ct = rs.getInt("completion_tokens");
        Integer completionTokens = rs.wasNull() ? null : ct;
        int tt = rs.getInt("total_tokens");
        Integer totalTokens = rs.wasNull() ? null : tt;
        return new LlmCallLog(
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("task_id"),
                rs.getInt("iteration"),
                rs.getString("tool_name"),
                rs.getString("route_type"),
                rs.getString("model_choice"),
                rs.getString("candidate_chain"),
                rs.getString("injected_tools"),
                rs.getString("attempts"),
                rs.getString("reasons"),
                rs.getString("chosen_model"),
                rs.getString("status"),
                rs.getString("error_msg"),
                rs.getString("decision_rationale"),
                rs.getString("prompt_content"),
                rs.getString("raw_prompt"),
                rs.getString("response_content"),
                promptTokens,
                completionTokens,
                totalTokens,
                rs.getLong("latency_ms"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at").toInstant());
    }

    private static int bind(PreparedStatement ps, List<Object> params) throws SQLException {
        int idx = 1;
        for (Object p : params) {
            ps.setObject(idx++, p);
        }
        return idx;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    @Override
    public void destroy() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // 关闭失败无需处理
        }
    }
}
