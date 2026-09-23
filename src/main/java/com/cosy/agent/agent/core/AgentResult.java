package com.cosy.agent.agent.core;

import java.util.List;

/**
 * Agent 运行结果：答案 + 完整推理轨迹（Step 2 起 trace 承载 ReAct 轨迹）。
 */
public record AgentResult(
        String sessionId,
        String answer,
        AgentState state,
        List<AgentMessage> trace,
        int iterations,
        long costMs,
        String errorMessage) {

    public static AgentResult frameworkReady(String sessionId, String answer) {
        return new AgentResult(sessionId, answer, AgentState.COMPLETED, List.of(), 0, 0L, null);
    }

    public static AgentResult failure(String sessionId, String errorMessage) {
        return new AgentResult(sessionId, null, AgentState.FAILED, List.of(), 0, 0L, errorMessage);
    }
}
