package com.cosy.agent.service;

import com.cosy.agent.agent.core.AgentContext;
import com.cosy.agent.agent.core.AgentResult;
import com.cosy.agent.agent.core.ReActAgent;
import com.cosy.agent.config.AgentProperties;
import org.springframework.stereotype.Service;

/**
 * Agent 编排入口：承接请求 → 构建上下文 → 执行 ReAct 循环 → 返回结果。
 */
@Service
public class AgentOrchestrator {

    private final ReActAgent reactAgent;
    private final AgentProperties properties;

    public AgentOrchestrator(ReActAgent reactAgent, AgentProperties properties) {
        this.reactAgent = reactAgent;
        this.properties = properties;
    }

    public AgentResult chat(String sessionId, String userId, String input) {
        AgentContext context = AgentContext.create(sessionId, userId, properties.maxIterations());
        return reactAgent.run(context, input);
    }
}
