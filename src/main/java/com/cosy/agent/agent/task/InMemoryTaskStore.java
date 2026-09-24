package com.cosy.agent.agent.task;

import com.cosy.agent.agent.core.AgentMessage;
import com.cosy.agent.agent.core.AgentState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存任务存储（默认实现，无外部依赖）：进程内保存任务主记录与轨迹，
 * 供单测、轻量运行与"未配置 PostgreSQL"时使用；重启即失，生产请切 JdbcTaskStore。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.task", name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryTaskStore implements TaskStore {

    private static final AtomicLong SEQ = new AtomicLong();

    private final Map<String, TaskDetail> tasks = new ConcurrentHashMap<>();

    @Override
    public AgentTask createTask(String sessionId, String userId, String input) {
        String taskId = "task-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet();
        AgentTask task = AgentTask.created(taskId, sessionId, userId, input);
        tasks.put(taskId, new TaskDetail(task, List.of()));
        return task;
    }

    @Override
    public void updateTask(String taskId, AgentState state, String output, int iterations, long costMs,
                           String errorMessage) {
        TaskDetail detail = tasks.get(taskId);
        if (detail == null) {
            return;
        }
        AgentTask task = detail.task();
        Instant now = Instant.now();
        boolean terminal = state == AgentState.COMPLETED || state == AgentState.FAILED
                || state == AgentState.TIMEOUT || state == AgentState.CANCELLED;
        AgentTask updated = new AgentTask(task.taskId(), task.sessionId(), task.userId(), state,
                task.input(), output, iterations, costMs, errorMessage, task.createdAt(), now,
                terminal ? now : task.finishedAt());
        tasks.put(taskId, new TaskDetail(updated, detail.trace()));
    }

    @Override
    public void appendTrace(String taskId, List<AgentMessage> trace) {
        TaskDetail detail = tasks.get(taskId);
        if (detail == null || trace == null || trace.isEmpty()) {
            return;
        }
        // 幂等：若已存在相同消息序列则跳过（以长度与首条时间戳为判据）
        if (detail.trace().size() >= trace.size() && !detail.trace().isEmpty()) {
            return;
        }
        tasks.put(taskId, new TaskDetail(detail.task(), List.copyOf(trace)));
    }

    @Override
    public Optional<TaskDetail> findById(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<AgentTask> findBySession(String sessionId, int limit) {
        return tasks.values().stream()
                .map(TaskDetail::task)
                .filter(t -> t.sessionId().equals(sessionId))
                .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public Optional<SessionSummary> findSession(String sessionId) {
        var group = tasks.values().stream()
                .map(TaskDetail::task)
                .filter(t -> t.sessionId().equals(sessionId))
                .toList();
        if (group.isEmpty()) {
            return Optional.empty();
        }
        AgentTask earliest = group.stream()
                .min(Comparator.comparing(AgentTask::createdAt)).orElseThrow();
        AgentTask latest = group.stream()
                .max(Comparator.comparing(AgentTask::updatedAt)).orElseThrow();
        return Optional.of(new SessionSummary(latest.sessionId(), earliest.input(),
                latest.state(), latest.updatedAt()));
    }

    @Override
    public void deleteSession(String sessionId) {
        tasks.entrySet().removeIf(e -> e.getValue().task().sessionId().equals(sessionId));
    }

    @Override
    public List<SessionSummary> findSessions(int limit) {
        return tasks.values().stream()
                .map(TaskDetail::task)
                .collect(Collectors.groupingBy(AgentTask::sessionId))
                .values().stream()
                .map(group -> {
                    AgentTask earliest = group.stream()
                            .min(Comparator.comparing(AgentTask::createdAt)).orElseThrow();
                    AgentTask latest = group.stream()
                            .max(Comparator.comparing(AgentTask::updatedAt)).orElseThrow();
                    return new SessionSummary(latest.sessionId(), earliest.input(),
                            latest.state(), latest.updatedAt());
                })
                .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
                .limit(Math.max(1, limit))
                .toList();
    }
}
