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
}
