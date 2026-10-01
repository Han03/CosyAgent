/**
 * CosyAgent 服务端：基于 Spring AI 的 ReAct 企业级智能体系统。
 *
 * <p><b>分层总览（自顶向下）</b></p>
 * <ol>
 *   <li>{@code controller} —— HTTP 入口（REST + SSE 流式）</li>
 *   <li>{@code service} —— 编排层：会话 / 任务 / 记忆装配</li>
 *   <li>{@code agent.core} —— ReAct 循环大脑（本项目核心）</li>
 *   <li>{@code agent.tool / agent.memory / agent.vector / agent.router /
 *       agent.task / agent.mock / agent.resilience} —— 能力层</li>
 *   <li>{@code config / common / security} —— 支撑层</li>
 * </ol>
 *
 * <p><b>推荐阅读路线</b>：详见 {@code docs/代码阅读指南.md}。
 * 一句话主线：一次 HTTP 请求 → 编排层装配上下文 → ReAct 循环
 * （LLM 规划 → 工具执行 → 记忆/知识增强 → 多轮迭代）→ 任务持久化 → 流式返回。</p>
 */
package com.cosy.agent;
