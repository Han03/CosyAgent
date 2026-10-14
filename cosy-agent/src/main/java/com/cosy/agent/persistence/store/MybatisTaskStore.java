package com.cosy.agent.persistence.store;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cosy.agent.agent.core.AgentMessage;
import com.cosy.agent.agent.core.AgentState;
import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
import com.cosy.agent.agent.task.AgentTask;
import com.cosy.agent.agent.task.TaskStore;
import com.cosy.agent.persistence.entity.AgentTaskEntity;
import com.cosy.agent.persistence.entity.AgentTraceEntity;
import com.cosy.agent.persistence.mapper.AgentTaskMapper;
import com.cosy.agent.persistence.mapper.AgentTraceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MyBatis-Plus 任务/会话存储（cosy.agent.persistence=mysql 时装配，替换 MysqlJdbcTaskStore）。
 *
 * <p>与手写 JDBC 版语义逐一对齐：幂等 appendTrace（查重跳过）、终态 CASE WHEN、
 * 会话聚合（title=最早任务输入/覆盖，state/updatedAt 取最新任务，置顶优先倒序）、
 * 删除会话级联轨迹。写操作保留 TASK 容错落点（重试/熔断/超时）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.persistence", name = "store", havingValue = "mysql")
public class MybatisTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisTaskStore.class);

    private final AgentTaskMapper taskMapper;
    private final AgentTraceMapper traceMapper;
    private final ResilienceSupport resilience;

    public MybatisTaskStore(AgentTaskMapper taskMapper, AgentTraceMapper traceMapper,
                            ResilienceSupport resilience) {
        this.taskMapper = taskMapper;
        this.traceMapper = traceMapper;
        this.resilience = resilience;
    }

    @Override
    public AgentTask createTask(String sessionId, String userId, String input) {
        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AgentTask task = AgentTask.created(taskId, sessionId, userId, input);
        resilience.execute(ResilienceTarget.TASK, () -> {
            AgentTaskEntity e = new AgentTaskEntity();
            e.taskId = task.taskId();
            e.sessionId = task.sessionId();
            e.userId = task.userId();
            e.state = task.state().name();
            e.input = task.input();
            e.createdAt = task.createdAt();
            e.updatedAt = task.updatedAt();
            e.pinned = false;
            taskMapper.insert(e);
            return null;
        });
        return task;
    }

    @Override
    public void updateTask(String taskId, AgentState state, String output, int iterations, long costMs,
                           String errorMessage) {
        resilience.execute(ResilienceTarget.TASK, () -> {
            boolean terminal = state == AgentState.COMPLETED || state == AgentState.FAILED
                    || state == AgentState.TIMEOUT || state == AgentState.CANCELLED;
            Instant now = Instant.now();
            taskMapper.updateState(taskId, state.name(), output, iterations, costMs, errorMessage,
                    terminal, Timestamp.from(now), Timestamp.from(now));
            return null;
        });
    }

    @Override
    public void appendTrace(String taskId, List<AgentMessage> trace) {
        if (trace == null || trace.isEmpty()) {
            return;
        }
        resilience.execute(ResilienceTarget.TASK, () -> {
            if (traceMapper.countByTask(taskId) > 0) {
                return null; // 幂等：已落库则跳过（防重复）
            }
            int seq = 0;
            for (AgentMessage m : trace) {
                AgentTraceEntity e = new AgentTraceEntity();
                e.taskId = taskId;
                e.seq = seq++;
                e.role = m.role().name();
                e.content = m.content();
                e.toolName = m.toolName();
                e.toolArguments = m.toolArguments();
                e.createdAt = m.timestamp() != null ? m.timestamp() : Instant.now();
                traceMapper.insert(e);
            }
            return null;
        });
    }

    @Override
    public Optional<TaskStore.TaskDetail> findById(String taskId) {
        return resilience.execute(ResilienceTarget.TASK, () -> {
            AgentTaskEntity te = taskMapper.selectById(taskId);
            if (te == null) {
                return Optional.<TaskStore.TaskDetail>empty();
            }
            List<AgentMessage> trace = new ArrayList<>();
            for (AgentTraceEntity e : traceMapper.selectByTask(taskId)) {
                trace.add(new AgentMessage(
                        AgentMessage.Role.valueOf(e.role),
                        e.content,
                        null,
                        e.toolName,
                        e.toolArguments,
                        e.createdAt));
            }
            return Optional.of(new TaskStore.TaskDetail(toTask(te), trace));
        });
    }

    @Override
    public List<AgentTask> findBySession(String sessionId, int limit) {
        return resilience.execute(ResilienceTarget.TASK, () -> taskMapper.selectList(
                        new QueryWrapper<AgentTaskEntity>()
                                .eq("session_id", sessionId)
                                .orderByDesc("updated_at")
                                .last("LIMIT " + Math.max(1, limit)))
                .stream().map(this::toTask).toList());
    }

    @Override
    public List<TaskStore.SessionSummary> findSessions(int limit) {
        return resilience.execute(ResilienceTarget.TASK, () -> {
            List<TaskStore.SessionSummary> list = new ArrayList<>();
            for (Map<String, Object> row : taskMapper.selectSessionSummaries(Math.max(1, limit))) {
                list.add(toSummary(row));
            }
            return list;
        });
    }

    @Override
    public Optional<TaskStore.SessionSummary> findSession(String sessionId) {
        return resilience.execute(ResilienceTarget.TASK, () -> {
            Map<String, Object> row = taskMapper.selectSessionSummary(sessionId);
            return row == null ? Optional.<TaskStore.SessionSummary>empty()
                    : Optional.of(toSummary(row));
        });
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        resilience.execute(ResilienceTarget.TASK, () -> {
            traceMapper.deleteBySession(sessionId);
            taskMapper.delete(new QueryWrapper<AgentTaskEntity>()
                    .eq("session_id", sessionId));
            return null;
        });
    }

    @Override
    public void pinSession(String sessionId, boolean pinned) {
        resilience.execute(ResilienceTarget.TASK, () -> {
            taskMapper.updatePinned(sessionId, pinned);
            return null;
        });
    }

    @Override
    public void renameSession(String sessionId, String title) {
        resilience.execute(ResilienceTarget.TASK, () -> {
            taskMapper.updateTitle(sessionId, title);
            return null;
        });
    }

    // ---- 映射 ----

    private AgentTask toTask(AgentTaskEntity e) {
        return new AgentTask(e.taskId, e.sessionId, e.userId, AgentState.valueOf(e.state),
                e.input, e.output, e.iterations == null ? 0 : e.iterations,
                e.costMs == null ? 0L : e.costMs, e.errorMessage, e.createdAt, e.updatedAt, e.finishedAt);
    }

    private TaskStore.SessionSummary toSummary(Map<String, Object> row) {
        return new TaskStore.SessionSummary(
                (String) row.get("session_id"),
                (String) row.get("title"),
                AgentState.valueOf((String) row.get("state")),
                toInstant(row.get("updated_at")),
                toBool(row.get("pinned")));
    }

    private static Instant toInstant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (v instanceof Instant i) {
            return i;
        }
        return Instant.parse(String.valueOf(v));
    }

    private static boolean toBool(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
