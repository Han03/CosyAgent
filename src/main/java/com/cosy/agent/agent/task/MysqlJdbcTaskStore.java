package com.cosy.agent.agent.task;

import com.cosy.agent.agent.core.AgentMessage;
import com.cosy.agent.agent.core.AgentState;
import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
import com.cosy.agent.config.TaskProperties;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MySQL 业务数据持久化（cosy.agent.task.store=mysql 时装配）：
 * 与 {@link JdbcTaskStore}(pg) 同构 —— 表 agent_task（任务主记录）+ agent_trace（执行轨迹审计），
 * 启动时 CREATE TABLE IF NOT EXISTS 自建表；原生 JDBC（连接参数 cosy.agent.task.mysql.*），
 * 不触发 DataSource 自动配置——未安装 MySQL 时应用以 memory 实现运行不受影响。
 *
 * <p>方言差异：BIGSERIAL → BIGINT AUTO_INCREMENT（MySQL 8 无 BIGSERIAL）；
 * CREATE INDEX 无 IF NOT EXISTS（经 information_schema 判存在再建）；
 * 终态 CASE 条件不用 PG 的 {@code ::boolean} 强转（JDBC setBoolean 两库兼容）。</p>
 *
 * <p>写入落在 TASK 容错落点（重试/熔断/超时），MySQL 故障不阻塞对话主链路
 * （任务持久化失败仅记日志，对话结果照常返回）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.task", name = "store", havingValue = "mysql")
public class MysqlJdbcTaskStore implements TaskStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MysqlJdbcTaskStore.class);

    private final String url;
    private final String username;
    private final String password;
    private final ResilienceSupport resilience;

    public MysqlJdbcTaskStore(TaskProperties properties, ResilienceSupport resilience) {
        TaskProperties.Mysql mysql = properties.mysql();
        this.url = mysql.url();
        this.username = mysql.username();
        this.password = mysql.password();
        this.resilience = resilience;
        initSchema();
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private void initSchema() {
        try (Connection conn = open()) {
            conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS agent_task (
                        task_id       VARCHAR(64) PRIMARY KEY,
                        session_id    VARCHAR(64) NOT NULL,
                        user_id       VARCHAR(64) NOT NULL,
                        state         VARCHAR(32) NOT NULL,
                        input         TEXT,
                        output        TEXT,
                        iterations    INT DEFAULT 0,
                        cost_ms       BIGINT DEFAULT 0,
                        error_message TEXT,
                        created_at    TIMESTAMP DEFAULT now(),
                        updated_at    TIMESTAMP DEFAULT now(),
                        finished_at   TIMESTAMP,
                        pinned        TINYINT(1) DEFAULT 0,
                        title_override VARCHAR(256)
                    )""");
            ensureColumn(conn, "pinned",
                    "ALTER TABLE agent_task ADD COLUMN pinned TINYINT(1) DEFAULT 0");
            ensureColumn(conn, "title_override",
                    "ALTER TABLE agent_task ADD COLUMN title_override VARCHAR(256)");
            conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS agent_trace (
                        id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id       VARCHAR(64) NOT NULL REFERENCES agent_task(task_id),
                        seq           INT NOT NULL,
                        role          VARCHAR(16) NOT NULL,
                        content       TEXT,
                        tool_name     VARCHAR(128),
                        tool_arguments TEXT,
                        created_at    TIMESTAMP DEFAULT now()
                    )""");
            // MySQL 8 无 CREATE INDEX IF NOT EXISTS：按 information_schema 判存在再建
            ensureIndex(conn, "agent_trace", "idx_agent_trace_task", "task_id, seq");
            ensureIndex(conn, "agent_task", "idx_agent_task_session", "session_id, updated_at");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化任务持久化表失败（store=mysql）: " + e.getMessage(), e);
        }
    }

    private void ensureIndex(Connection conn, String table, String indexName, String columns) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?""")) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    return;
                }
            }
        }
        conn.createStatement().execute("CREATE INDEX " + indexName + " ON " + table + " (" + columns + ")");
    }

    @Override
    public AgentTask createTask(String sessionId, String userId, String input) {
        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AgentTask task = AgentTask.created(taskId, sessionId, userId, input);
        resilience.execute(ResilienceTarget.TASK, () -> {
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO agent_task
                             (task_id, session_id, user_id, state, input, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, task.taskId());
                ps.setString(2, task.sessionId());
                ps.setString(3, task.userId());
                ps.setString(4, task.state().name());
                ps.setString(5, task.input());
                ps.setTimestamp(6, Timestamp.from(task.createdAt()));
                ps.setTimestamp(7, Timestamp.from(task.updatedAt()));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("创建任务记录失败: " + e.getMessage(), e);
            }
            return null;
        });
        return task;
    }

    @Override
    public void updateTask(String taskId, AgentState state, String output, int iterations, long costMs,
                           String errorMessage) {
        resilience.execute(ResilienceTarget.TASK, () -> {
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement("""
                         UPDATE agent_task SET state = ?, output = ?, iterations = ?, cost_ms = ?,
                             error_message = ?, updated_at = ?,
                             finished_at = CASE WHEN ? THEN ? ELSE finished_at END
                         WHERE task_id = ?""")) {
                boolean terminal = state == AgentState.COMPLETED || state == AgentState.FAILED
                        || state == AgentState.TIMEOUT || state == AgentState.CANCELLED;
                Instant now = Instant.now();
                ps.setString(1, state.name());
                ps.setString(2, output);
                ps.setInt(3, iterations);
                ps.setLong(4, costMs);
                ps.setString(5, errorMessage);
                ps.setTimestamp(6, Timestamp.from(now));
                ps.setBoolean(7, terminal);
                ps.setTimestamp(8, Timestamp.from(now));
                ps.setString(9, taskId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新任务记录失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    @Override
    public void appendTrace(String taskId, List<AgentMessage> trace) {
        if (trace == null || trace.isEmpty()) {
            return;
        }
        resilience.execute(ResilienceTarget.TASK, () -> {
            try (Connection conn = open()) {
                conn.setAutoCommit(false);
                try (PreparedStatement count = conn.prepareStatement(
                        "SELECT COUNT(*) FROM agent_trace WHERE task_id = ?")) {
                    count.setString(1, taskId);
                    try (ResultSet rs = count.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) > 0) {
                            return null; // 幂等：已落库则跳过（防重复）
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO agent_trace (task_id, seq, role, content, tool_name, tool_arguments, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                    int seq = 0;
                    for (AgentMessage m : trace) {
                        ps.setString(1, taskId);
                        ps.setInt(2, seq++);
                        ps.setString(3, m.role().name());
                        ps.setString(4, m.content());
                        ps.setString(5, m.toolName());
                        ps.setString(6, m.toolArguments());
                        ps.setTimestamp(7, Timestamp.from(m.timestamp() != null ? m.timestamp() : Instant.now()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("写入执行轨迹失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    @Override
    public Optional<TaskDetail> findById(String taskId) {
        return resilience.execute(ResilienceTarget.TASK, () -> {
            try (Connection conn = open()) {
                AgentTask task;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT task_id, session_id, user_id, state, input, output, iterations, cost_ms,
                               error_message, created_at, updated_at, finished_at
                        FROM agent_task WHERE task_id = ?""")) {
                    ps.setString(1, taskId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.<TaskDetail>empty();
                        }
                        task = mapTask(rs);
                    }
                }
                List<AgentMessage> trace = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT role, content, tool_name, tool_arguments, created_at
                        FROM agent_trace WHERE task_id = ? ORDER BY seq""")) {
                    ps.setString(1, taskId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            trace.add(new AgentMessage(
                                    AgentMessage.Role.valueOf(rs.getString("role")),
                                    rs.getString("content"),
                                    null,
                                    rs.getString("tool_name"),
                                    rs.getString("tool_arguments"),
                                    rs.getTimestamp("created_at").toInstant()));
                        }
                    }
                }
                return Optional.of(new TaskDetail(task, trace));
            } catch (SQLException e) {
                throw new IllegalStateException("查询任务记录失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public List<AgentTask> findBySession(String sessionId, int limit) {
        return resilience.execute(ResilienceTarget.TASK, () -> {
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement("""
                         SELECT task_id, session_id, user_id, state, input, output, iterations, cost_ms,
                                error_message, created_at, updated_at, finished_at
                         FROM agent_task WHERE session_id = ? ORDER BY updated_at DESC LIMIT ?""")) {
                ps.setString(1, sessionId);
                ps.setInt(2, Math.max(1, limit));
                List<AgentTask> list = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapTask(rs));
                    }
                }
                return list;
            } catch (SQLException e) {
                throw new IllegalStateException("按会话查询任务失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Optional<SessionSummary> findSession(String sessionId) {
        return resilience.execute(ResilienceTarget.TASK, () -> {
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement("""
                         SELECT t.session_id,
                                COALESCE(
                                  (SELECT t2.title_override FROM agent_task t2
                                    WHERE t2.session_id = t.session_id
                                      AND t2.title_override IS NOT NULL
                                    ORDER BY t2.created_at ASC LIMIT 1),
                                  (SELECT t2.input FROM agent_task t2
                                    WHERE t2.session_id = t.session_id
                                    ORDER BY t2.created_at ASC LIMIT 1)) AS title,
                                t.state, t.updated_at, t.pinned
                         FROM agent_task t
                         WHERE t.session_id = ?
                           AND t.updated_at = (SELECT MAX(t3.updated_at) FROM agent_task t3
                                                WHERE t3.session_id = t.session_id)""")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<SessionSummary>empty();
                    }
                    return Optional.of(new SessionSummary(
                            rs.getString("session_id"),
                            rs.getString("title"),
                            AgentState.valueOf(rs.getString("state")),
                            rs.getTimestamp("updated_at").toInstant(),
                            rs.getBoolean("pinned")));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询会话摘要失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void deleteSession(String sessionId) {
        resilience.execute(ResilienceTarget.TASK, () -> {
            try (Connection conn = open()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM agent_trace WHERE task_id IN (SELECT task_id FROM agent_task WHERE session_id = ?)")) {
                    ps.setString(1, sessionId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM agent_task WHERE session_id = ?")) {
                    ps.setString(1, sessionId);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("删除会话失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    @Override
    public void pinSession(String sessionId, boolean pinned) {
        resilience.execute(ResilienceTarget.TASK, () -> {
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE agent_task SET pinned = ? WHERE session_id = ?")) {
                ps.setBoolean(1, pinned);
                ps.setString(2, sessionId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("置顶会话失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    @Override
    public void renameSession(String sessionId, String title) {
        resilience.execute(ResilienceTarget.TASK, () -> {
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE agent_task SET title_override = ? WHERE session_id = ?")) {
                ps.setString(1, title);
                ps.setString(2, sessionId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("重命名会话失败: " + e.getMessage(), e);
            }
            return null;
        });
    }

    @Override
    public List<SessionSummary> findSessions(int limit) {
        return resilience.execute(ResilienceTarget.TASK, () -> {
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement("""
                         SELECT t.session_id,
                                COALESCE(
                                  (SELECT t2.title_override FROM agent_task t2
                                    WHERE t2.session_id = t.session_id
                                      AND t2.title_override IS NOT NULL
                                    ORDER BY t2.created_at ASC LIMIT 1),
                                  (SELECT t2.input FROM agent_task t2
                                    WHERE t2.session_id = t.session_id
                                    ORDER BY t2.created_at ASC LIMIT 1)) AS title,
                                t.state, t.updated_at, t.pinned
                         FROM agent_task t
                         WHERE t.updated_at = (SELECT MAX(t3.updated_at) FROM agent_task t3
                                                WHERE t3.session_id = t.session_id)
                         ORDER BY t.pinned DESC, t.updated_at DESC LIMIT ?""")) {
                ps.setInt(1, Math.max(1, limit));
                List<SessionSummary> list = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new SessionSummary(
                                rs.getString("session_id"),
                                rs.getString("title"),
                                AgentState.valueOf(rs.getString("state")),
                                rs.getTimestamp("updated_at").toInstant(),
                                rs.getBoolean("pinned")));
                    }
                }
                return list;
            } catch (SQLException e) {
                throw new IllegalStateException("查询会话列表失败: " + e.getMessage(), e);
            }
        });
    }
    private void ensureColumn(Connection conn, String column, String alterSql) throws SQLException {
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = 'agent_task' AND column_name = ?")) {
            check.setString(1, column);
            try (ResultSet rs = check.executeQuery()) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    conn.createStatement().execute(alterSql);
                }
            }
        }
    }

    private AgentTask mapTask(ResultSet rs) throws SQLException {
        return new AgentTask(
                rs.getString("task_id"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                AgentState.valueOf(rs.getString("state")),
                rs.getString("input"),
                rs.getString("output"),
                rs.getInt("iterations"),
                rs.getLong("cost_ms"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null);
    }

    @Override
    public void destroy() {
        // DriverManager 直连无需显式关闭
    }
}
