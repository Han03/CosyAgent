/**
 * HTTP 入口层：REST + SSE 流式，直接委托 service 层，不承载业务逻辑。
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.controller.AgentController} —— 会话 / 任务 / 消息 / 工具 /
 *       同步聊天 / SSE 流式聊天（{@code POST /api/agent/chat/stream}）</li>
 *   <li>{@link com.cosy.agent.controller.KnowledgeController} —— 知识库写入与检索
 *       （{@code /api/agent/knowledge}）</li>
 * </ol>
 *
 * <p><b>理解入口</b>：从 {@code AgentController.chatStream} 开始追踪
 * “一次请求如何进入 ReAct 循环”，这是全项目最佳阅读起点。</p>
 */
package com.cosy.agent.controller;
