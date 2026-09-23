package com.cosy.agent.agent.core;

import java.util.List;

/**
 * Agent 运行结果：答案 + 完整推理轨迹（Step 2 起 trace 承载 ReAct 轨迹）。
 *
 * @param taskId 任务 ID（Step 6 状态持久化，由编排层生成后经 AgentContext 透传；无持久化时为空）
 */
public record AgentResult(
        String sessionId,
        String answer,
        AgentState state,
        List<AgentMessage> trace,
        int iterations,
        long costMs,
        String errorMessage,
        String taskId) {

    public static AgentResult frameworkReady(String sessionId, String answer) {
        return new AgentResult(sessionId, answer, AgentState.COMPLETED, List.of(), 0, 0L, null, null);
    }

    public static AgentResult failure(String sessionId, String errorMessage) {
        return new AgentResult(sessionId, null, AgentState.FAILED, List.of(), 0, 0L, errorMessage, null);
    }
}
