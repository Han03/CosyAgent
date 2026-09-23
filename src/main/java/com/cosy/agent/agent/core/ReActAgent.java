package com.cosy.agent.agent.core;

import java.util.List;

/**
 * ReAct 智能体执行器契约（Step 2 实现）。
 *
 * <p>实现职责：Thought → Action → Observation 循环，调用 LLM 自主规划、
 * 通过 ToolRegistry 执行工具、携带记忆上下文多轮迭代，直到产出最终答案或触发终止条件。</p>
 */
public interface ReActAgent {

    /** Agent 名称标识 */
    String name();

    /** 执行一次任务：给定上下文与用户输入，返回答案与执行轨迹 */
    default AgentResult run(AgentContext context, String userInput) {
        return run(context, userInput, List.of());
    }

    /**
     * 执行一次任务，可携带历史轨迹（Step 6 断点恢复）：
     * 历史 USER/ASSISTANT/TOOL 消息作为模型上下文注入（系统提示之后、本次输入之前）。
     */
    AgentResult run(AgentContext context, String userInput, List<AgentMessage> history);
}
