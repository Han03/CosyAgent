package com.cosy.agent.agent.capability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 能力注册中心配置（prefix: cosy.agent.capability）。
 *
 * @param enabled      总开关（默认 false：注册 API 与动态注入不生效，系统与现状等价）
 * @param token        注册鉴权令牌（X-Capability-Token；空 = 不校验）
 * @param defaultMode  注册未声明 mode 时的兜底：ap（默认）| cp
 * @param heartbeat    心跳调度：interval 扫描周期 / suspectAfter 未续约进入可疑 / expireAfter 未续约摘除
 * @param probe        CP 主动健康检查（探测 /healthz，失败标 DOWN 不摘除）
 * @param store        CP 持久化存储：memory（默认，仅内存）| mysql（落库 + 启动恢复）
 * @param namespace    默认命名空间（注册未声明时兜底）
 * @param callTimeout  远程能力调用超时（套 TOOL 容错 TimeLimiter 之前的上限）
 * @param mysql        store=mysql 时的连接参数（与 task store 同款结构）
 */
@ConfigurationProperties(prefix = "cosy.agent.capability")
public record CapabilityProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String token,
        @DefaultValue("ap") String defaultMode,
        Heartbeat heartbeat,
        Probe probe,
        @DefaultValue("memory") String store,
        @DefaultValue("default") String namespace,
        @DefaultValue("5s") Duration callTimeout,
        Mysql mysql) {

    public record Heartbeat(
            @DefaultValue("5s") Duration interval,
            @DefaultValue("15s") Duration suspectAfter,
            @DefaultValue("30s") Duration expireAfter) {
    }

    public record Probe(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60s") Duration interval) {
    }

    public record Mysql(
            @DefaultValue("jdbc:mysql://localhost:3306/cosy?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai") String url,
            @DefaultValue("cosy") String username,
            @DefaultValue("cosy") String password) {
    }

    public static final Mysql DEFAULT_MYSQL =
            new Mysql("jdbc:mysql://localhost:3306/cosy?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai", "cosy", "cosy");

    public Mysql mysql() {
        return mysql == null ? DEFAULT_MYSQL : mysql;
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
