package com.cosy.agent.agent.task;

import com.cosy.agent.agent.core.AgentState;

import java.time.Instant;

/**
 * 任务记录（Step 6 状态持久化，设计方案 §8）：一次 Agent 运行在持久化层的投影。
 *
 * <p>状态机（复用 {@link AgentState}）：INIT（创建）→ RUNNING（执行中）→
 * COMPLETED / FAILED / TIMEOUT / CANCELLED（终态）；状态迁移由 AgentOrchestrator 驱动。</p>
 *
 * @param taskId       任务 ID（全局唯一）
 * @param sessionId    会话 ID
 * @param userId       用户 ID
 * @param state        当前状态（AgentState）
 * @param input        用户输入
 * @param output       最终回答（终态时非空，FAILED 时为 null）
 * @param iterations   实际迭代轮数
 * @param costMs       执行耗时（毫秒）
 * @param errorMessage 失败原因（FAILED 时非空）
 * @param createdAt    创建时间
 * @param updatedAt    最近更新时间
 * @param finishedAt   完成时间（终态时非空）
 */
public record AgentTask(
        String taskId,
        String sessionId,
        String userId,
        AgentState state,
        String input,
        String output,
        int iterations,
        long costMs,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {

    public static AgentTask created(String taskId, String sessionId, String userId, String input) {
        Instant now = Instant.now();
        return new AgentTask(taskId, sessionId, userId, AgentState.INIT, input, null, 0, 0L, null,
                now, now, null);
    }
}
