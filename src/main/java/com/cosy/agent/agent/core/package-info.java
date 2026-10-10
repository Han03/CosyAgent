/**
 * ReAct 智能体核心（ / ）。
 *
 * <p>实现 Thought → Action → Observation 循环：模型自主规划、工具执行、
 * 携带记忆与知识上下文多轮迭代，直到产出最终答案或触发终止条件。</p>
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.agent.core.AgentContext} —— 一次运行的上下文
 *       （会话 / 用户 / 迭代上限 / 任务 / Mock 覆盖 / 模型选择）</li>
 *   <li>{@link com.cosy.agent.agent.core.ReActAgent} —— 执行器契约（接口）</li>
 *   <li>{@link com.cosy.agent.agent.core.DefaultReActAgent} —— 循环实现（本项目大脑，最重要）</li>
 *   <li>{@link com.cosy.agent.agent.core.AgentStreamEvent} —— SSE 流式事件协议（客户端渲染依据）</li>
 *   <li>{@link com.cosy.agent.agent.core.AgentMessage} / {@link com.cosy.agent.agent.core.AgentResult} /
 *       {@link com.cosy.agent.agent.core.AgentState} / {@link com.cosy.agent.agent.core.AgentEventListener} —— 数据与回调契约</li>
 * </ol>
 *
 * <p><b>依赖</b>：agent.tool（工具）、agent.memory（记忆）、agent.vector（知识检索）、
 * agent.router（模型路由）、agent.mock（Mock）、agent.resilience（容错）。</p>
 */
package com.cosy.agent.agent.core;
