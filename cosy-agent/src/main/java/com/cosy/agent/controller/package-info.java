/**
 * HTTP 入口层：REST + SSE 流式，直接委托 service 层，不承载业务逻辑。
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.controller.AgentController} —— 会话 / 任务 / 消息 / 工具 /
 *       同步聊天 / SSE 流式聊天（{@code POST /api/agent/chat/stream}）</li>
 * </ol>
 *
 * <p><b>说明</b>：知识库接口（{@code /api/agent/knowledge}）P3.1 起由独立服务
 * cosy-knowledge 提供（网关路由到该服务，本包不再承载）。</p>
 *
 * <p><b>理解入口</b>：从 {@code AgentController.chatStream} 开始追踪
 * “一次请求如何进入 ReAct 循环”，这是全项目最佳阅读起点。</p>
 */
package com.cosy.agent.controller;
