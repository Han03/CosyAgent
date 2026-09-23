package com.cosy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
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
 * @param mock            LLM 端到端 Mock 模块配置（Step M 生效）
 */
@ConfigurationProperties(prefix = "cosy.agent")
public record AgentProperties(
        @DefaultValue("8") int maxIterations,
        @DefaultValue("30s") Duration toolTimeout,
        @DefaultValue("30m") Duration sessionTimeout,
        @DefaultValue("180d") Duration longTermTimeout,
        @DefaultValue("10m") Duration workingTimeout,
        @NestedConfigurationProperty Mock mock) {

    /**
     * LLM 端到端 Mock 模块配置（prefix: cosy.agent.mock）。
     *
     * @param enabled     总开关：true 走模拟模型，false 走真实 LLM
     * @param mode        random：全随机（演示）；scripted：固定剧本+种子（CI 可复现）
     * @param script      scripted 模式指定剧本 id（random 时忽略）
     * @param seed        scripted 模式随机种子
     * @param maxTurns    mock 自身回合上限
     * @param probability 行为随机概率
     */
    public record Mock(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("random") String mode,
            @DefaultValue("time") String script,
            @DefaultValue("42") long seed,
            @DefaultValue("8") int maxTurns,
            @NestedConfigurationProperty Probability probability) {

        public static final Mock DEFAULT = new Mock(false, "random", "time", 42, 8, new Probability(0.4, 0.1, 0.1, 0.05));

        public record Probability(
                @DefaultValue("0.4") double extraTurn,
                @DefaultValue("0.1") double unknownTool,
                @DefaultValue("0.1") double multiTool,
                @DefaultValue("0.05") double error) {
        }
    }
}
