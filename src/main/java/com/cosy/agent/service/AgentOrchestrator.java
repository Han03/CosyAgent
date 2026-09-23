package com.cosy.agent.service;

import com.cosy.agent.agent.core.AgentContext;
import com.cosy.agent.agent.core.AgentResult;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.config.AgentProperties;
import org.springframework.stereotype.Service;

/**
 * Agent 编排入口：承接请求 → 构建上下文 → 执行 ReAct 循环 → 返回结果。
 *
 * <p>Step 1：仅完成上下文构建与框架就绪检查；
 * Step 2 起注入 {@code ReActAgent} 执行完整推理循环。</p>
 */
@Service
public class AgentOrchestrator {

    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;

    public AgentOrchestrator(ToolRegistry toolRegistry, AgentProperties properties) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    public AgentResult chat(String sessionId, String userId, String input) {
        AgentContext context = AgentContext.create(sessionId, userId, properties.maxIterations());
        // TODO(Step 2): 注入 ReActAgent 并执行 Thought → Action → Observation 循环
        String answer = "Step 1 基础框架运行正常。已注册工具: " + toolRegistry.names()
                + "；ReAct 推理循环将在 Step 2 接入（LLM 规划 + 工具调用 + 多轮迭代）。"
                + " 当前配置: 最大迭代 " + properties.maxIterations() + " 轮。";
        return AgentResult.frameworkReady(sessionId, answer);
    }
}
