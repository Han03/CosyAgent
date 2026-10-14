/**
 * 配置装配层：{@code @ConfigurationProperties} 与 Bean 装配。
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.config.AgentProperties} —— 核心配置（prefix {@code cosy.agent}）：
 *       迭代上限 / 工具超时 / 会话 TTL / Mock 参数</li>
 *   <li>{@link com.cosy.agent.config.AgentChatConfig} —— Bean 装配：ChatModel、工具桥接注入、
 *       AgentContext 工厂、向量化器等（理解“配置如何变成运行对象”）</li>
 *   <li>{@link com.cosy.agent.config.TaskProperties} / {@link com.cosy.agent.config.VectorProperties} /
 *       {@link com.cosy.agent.config.ModelRoutingProperties} / {@link com.cosy.agent.config.SecurityProperties}
 *       —— 各能力层配置</li>
 *   <li>{@link com.cosy.agent.config.ModelRoutingConfig} —— 路由 Bean 装配（管理 API 暴露）</li>
 * </ol>
 */
package com.cosy.agent.config;
