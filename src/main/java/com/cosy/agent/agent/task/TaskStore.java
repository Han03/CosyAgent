package com.cosy.agent.agent.task;

import com.cosy.agent.agent.core.AgentMessage;
import com.cosy.agent.agent.core.AgentState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 任务存储契约（Step 6 状态持久化）：任务主记录 + 执行轨迹（审计）。
 *
 * <p>持久化层抽象，提供两种实现：
 * {@code InMemoryTaskStore}（默认，无外部依赖，供单测与轻量运行）与
 * {@code JdbcTaskStore}（PostgreSQL 生产形态，cosy.agent.task.store=pg 时装配，
 * 表 agent_task / agent_trace，见设计方案 §8）。</p>
 */
public interface TaskStore {

    /** 创建任务（状态 INIT），返回已持久化的任务记录 */
    AgentTask createTask(String sessionId, String userId, String input);

    /** 更新任务状态与运行结果（终态时写入 output / costMs / errorMessage / finishedAt） */
    void updateTask(String taskId, AgentState state, String output, int iterations, long costMs,
                    String errorMessage);

    /** 追加执行轨迹（审计：逐条写入 agent_trace，幂等——重复调用不重复落库） */
    void appendTrace(String taskId, List<AgentMessage> trace);

    /** 按任务 ID 查询（含轨迹） */
    Optional<TaskDetail> findById(String taskId);

    /** 按会话查询任务列表（更新时间倒序，limit 上限） */
    List<AgentTask> findBySession(String sessionId, int limit);

    /**
     * 会话列表（会话 = 同一 sessionId 的任务分组；title = 该会话最早任务输入，即首条消息；
     * state/updatedAt 取该会话最新任务；整体按更新时间倒序）。
     */
    List<SessionSummary> findSessions(int limit);

    /** 删除会话：移除该会话全部任务与轨迹（含审计）；会话不存在时静默返回 */
    void deleteSession(String sessionId);

    /**
     * 会话全量消息（进入会话恢复用）：按消息时间戳升序合并该会话全部任务轨迹。
     * 默认实现基于 findBySession + findById 组装；存储实现可覆写为单次 SQL。
     */
    default List<AgentMessage> findMessages(String sessionId) {
        return findBySession(sessionId, 100_000).stream()
                .flatMap(t -> findById(t.taskId()).map(TaskDetail::trace)
                        .orElse(List.of()).stream())
                .sorted(java.util.Comparator.comparing(
                        AgentMessage::timestamp,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /** 会话摘要（客户端会话列表渲染用） */
    record SessionSummary(String sessionId, String title, AgentState state, Instant updatedAt) {
    }

    /** 任务主记录 + 轨迹明细 */
    record TaskDetail(AgentTask task, List<AgentMessage> trace) {
    }
}
