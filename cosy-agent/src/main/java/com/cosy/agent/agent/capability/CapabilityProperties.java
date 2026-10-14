package com.cosy.agent.agent.capability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Agent 侧能力注入配置（prefix: cosy.agent.capability，P3.2 精简版）。
 *
 * <p>P3.2 起注册中心独立为 cosy-capability 服务（8282）：agent 不再持有
 * 注册/心跳/探活逻辑，仅保留「开关 + 同步周期」，由 {@link CapabilitySync}
 * 周期拉取能力清单注入 ToolRegistry。</p>
 *
 * @param enabled      动态能力工具注入总开关（默认 false：不拉取清单，行为与现状等价）
 * @param syncInterval 能力清单同步周期（拉取 → diff → ToolRegistry 注入/摘除）
 */
@ConfigurationProperties(prefix = "cosy.agent.capability")
public record CapabilityProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("10s") Duration syncInterval) {
}
