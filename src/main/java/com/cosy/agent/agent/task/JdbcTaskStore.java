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
 * PostgreSQL 任务持久化（生产形态，cosy.agent.task.store=pg 时装配）：
 * 表 agent_task（任务主记录）+ agent_trace（执行轨迹审计，设计方案 §8），
 * 启动时 CREATE TABLE IF NOT EXISTS 自建表；原生 JDBC（连接参数 cosy.agent.task.pg.*），
 * 不触发 Spring DataSource 自动配置——未安装 PostgreSQL 时应用以 memory 实现运行不受影响。
 *
 * <p>Step 6 起写入落在 TASK 容错落点（重试/熔断/超时），PG 故障不阻塞对话主链路
 * （任务持久化失败仅记日志，对话结果照常返回）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.task", name = "store", havingValue = "pg")
public class JdbcTaskStore implements TaskStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JdbcTaskStore.class);

    private final String url;
    private final String username;
    private final String password;
    private final ResilienceSupport resilience;

    public JdbcTaskStore(TaskProperties properties, ResilienceSupport resilience) {
        TaskProperties.Pg pg = properties.pg();
        this.url = pg.url();
        this.username = pg.username();
        this.password = pg.password();
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
                        finished_at   TIMESTAMP
                    )""");
            conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS agent_trace (
                        id           BIGSERIAL PRIMARY KEY,
                        task_id      VARCHAR(64) NOT NULL REFERENCES agent_task(task_id),
                        seq          INT NOT NULL,
                        role         VARCHAR(16) NOT NULL,
                        content      TEXT,
                        tool_name    VARCHAR(128),
                        tool_arguments TEXT,
                        created_at   TIMESTAMP DEFAULT now()
                    )""");
            conn.createStatement().execute(
                    "CREATE INDEX IF NOT EXISTS idx_agent_trace_task ON agent_trace(task_id, seq)");
            conn.createStatement().execute(
                    "CREATE INDEX IF NOT EXISTS idx_agent_task_session ON agent_task(session_id, updated_at DESC)");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化任务持久化表失败（store=pg）: " + e.getMessage(), e);
        }
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
                             finished_at = CASE WHEN ?::boolean THEN ? ELSE finished_at END
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
