/**
 * 工具层（Step 2）：模型自主选择、宿主安全执行的函数调用能力。
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.agent.tool.AgentTool} —— 工具契约（名称 / 描述 / 参数 Schema / 执行）</li>
 *   <li>{@link com.cosy.agent.agent.tool.ToolRegistry} —— 工具注册表（Spring 自动收集 + 手工注册）</li>
 *   <li>{@link com.cosy.agent.agent.tool.ServerTimeTool} / {@link com.cosy.agent.agent.tool.ServerInfoTool} —— 示例工具</li>
 *   <li>{@link com.cosy.agent.agent.tool.AgentToolBridging} —— 桥接层：把 AgentTool 转为 LLM 的
 *       FunctionTool（JSON Schema），注入模型 ChatOptions</li>
 * </ol>
 *
 * <p><b>被依赖</b>：agent.core.DefaultReActAgent 执行工具时经 ToolRegistry 查找；
 * 工具定义经 AgentToolBridging 注入模型。容错走 ResilienceTarget.TOOL。</p>
 */
package com.cosy.agent.agent.tool;
