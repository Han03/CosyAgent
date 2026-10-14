package com.cosy.capability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 能力注册中心配置（prefix: cosy.agent.capability，P3.2 独立服务）。
 *
 * @param enabled     总开关（默认 false：注册 API 与动态工具注入不生效，系统与现状等价）
 * @param token       注册鉴权令牌（X-Capability-Token；空 = 不校验）
 * @param defaultMode 注册未声明 mode 时的兜底：ap（默认）| cp
 * @param heartbeat   心跳调度：interval 扫描周期 / suspectAfter 未续约进入可疑 / expireAfter 未续约摘除
 * @param probe       CP 主动健康检查（探测 /healthz，失败标 DOWN 不摘除）
 * @param namespace   默认命名空间（注册未声明时兜底）
 * @param callTimeout 远程能力调用超时（RestClient 读超时；候选链降级在 call 层）
 */
@ConfigurationProperties(prefix = "cosy.agent.capability")
public record CapabilityProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String token,
        @DefaultValue("ap") String defaultMode,
        Heartbeat heartbeat,
        Probe probe,
        @DefaultValue("default") String namespace,
        @DefaultValue("5s") Duration callTimeout) {

    public record Heartbeat(
            @DefaultValue("5s") Duration interval,
            @DefaultValue("15s") Duration suspectAfter,
            @DefaultValue("30s") Duration expireAfter) {
    }

    public record Probe(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60s") Duration interval) {
    }

    public Heartbeat heartbeat() {
        return heartbeat == null ? new Heartbeat(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30)) : heartbeat;
    }

    public Probe probe() {
        return probe == null ? new Probe(true, Duration.ofSeconds(60)) : probe;
    }

    public Duration heartbeatInterval() {
        return heartbeat().interval();
    }
}
