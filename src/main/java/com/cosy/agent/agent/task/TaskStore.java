package com.cosy.agent.agent.task;

import com.cosy.agent.agent.core.AgentMessage;
import com.cosy.agent.agent.core.AgentState;

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

    /** 任务主记录 + 轨迹明细 */
    record TaskDetail(AgentTask task, List<AgentMessage> trace) {
    }
}
