package com.cosy.agent.agent.core;

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
    AgentResult run(AgentContext context, String userInput);
}
