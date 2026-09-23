package com.cosy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * CosyAgent 核心配置项（prefix: cosy.agent）。
 *
 * @param maxIterations   ReAct 最大迭代轮数（Step 2 生效）
 * @param toolTimeout     单次工具调用超时（Step 5 生效）
 * @param sessionTimeout  会话记忆 TTL（Step 3 生效）
 * @param longTermTimeout 长期记忆 TTL（Step 3 生效）
 * @param workingTimeout  工作记忆 TTL（Step 3 生效）
 */
@ConfigurationProperties(prefix = "cosy.agent")
public record AgentProperties(
        @DefaultValue("8") int maxIterations,
        @DefaultValue("30s") Duration toolTimeout,
        @DefaultValue("30m") Duration sessionTimeout,
        @DefaultValue("180d") Duration longTermTimeout,
        @DefaultValue("10m") Duration workingTimeout) {
}
