package com.cosy.agent.agent.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次 Agent 运行的上下文：会话维度贯穿整个执行周期。
 *
 * @param mockOverride 请求级 Mock 覆盖（null=回退全局配置；true/false 由
 *                     X-Cosy-Mock 请求头注入，优先级高于 cosy.agent.mock.enabled）
 */
public record AgentContext(
        String sessionId,
        String userId,
        Map<String, Object> attributes,
        int maxIterations,
        Boolean mockOverride) {

    public static AgentContext create(String sessionId, String userId, int maxIterations) {
        return new AgentContext(sessionId, userId, new ConcurrentHashMap<>(), maxIterations, null);
    }

    /** 创建带任务 ID 的上下文（Step 6：编排层持久化任务时透传 taskId） */
    public static AgentContext create(String sessionId, String userId, int maxIterations, String taskId) {
        return create(sessionId, userId, maxIterations, taskId, null);
    }

    /** 创建带任务 ID 与请求级 Mock 开关的上下文 */
    public static AgentContext create(String sessionId, String userId, int maxIterations,
                                      String taskId, Boolean mockOverride) {
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        if (taskId != null) {
            attributes.put("taskId", taskId);
        }
        return new AgentContext(sessionId, userId, attributes, maxIterations, mockOverride);
    }
}
