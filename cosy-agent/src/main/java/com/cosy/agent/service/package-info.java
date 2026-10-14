/**
 * 编排层：controller 与 agent 能力层之间的装配器。
 *
 * <p>{@link com.cosy.agent.service.AgentOrchestrator} 把一次请求拆解为：
 * 解析会话 / 加载记忆历史 → 构造 AgentContext（含 Mock 覆盖、模型选择）→
 * 调 ReActAgent 执行 → 任务落库（创建 / 续跑 / 消息追加）→ 返回 AgentResult。
 * 是“无状态入口”与“有状态核心”之间的桥梁。</p>
 */
package com.cosy.agent.service;
