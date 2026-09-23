package com.cosy.agent.agent.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次 Agent 运行的上下文：会话维度贯穿整个执行周期。
 */
public record AgentContext(
        String sessionId,
        String userId,
        Map<String, Object> attributes,
        int maxIterations) {

    public static AgentContext create(String sessionId, String userId, int maxIterations) {
        return new AgentContext(sessionId, userId, new ConcurrentHashMap<>(), maxIterations);
    }

    /** 创建带任务 ID 的上下文（Step 6：编排层持久化任务时透传 taskId） */
    public static AgentContext create(String sessionId, String userId, int maxIterations, String taskId) {
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        if (taskId != null) {
            attributes.put("taskId", taskId);
        }
        return new AgentContext(sessionId, userId, attributes, maxIterations);
    }
}
