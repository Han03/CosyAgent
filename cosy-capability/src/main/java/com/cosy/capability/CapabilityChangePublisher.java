package com.cosy.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 能力变更事件发布器（P4.1）：状态变更（注册/更新/摘除/启停/探活摘除）后
 * 经 StreamBridge 发送到 topic {@code capability-change}，agent 订阅后即时同步。
 * 发送失败仅告警（agent 周期轮询兜底，不阻断本服务业务）。
 */
@Component
public class CapabilityChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(CapabilityChangePublisher.class);
    private static final String OUTPUT_BINDING = "capability-change-out";

    private final StreamBridge streamBridge;

    public CapabilityChangePublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    /** 发布变更事件（provider/capability 可为空——register 前能力清单未知） */
    public void publish(String provider, String capability, String action) {
        try {
            boolean sent = streamBridge.send(OUTPUT_BINDING,
                    new CapabilityChangeEvent(provider, capability, action, Instant.now().toString()));
            if (!sent) {
                log.warn("能力变更事件发送未受理（MQ 不可达？agent 周期轮询兜底）: action={} provider={}",
                        action, provider);
            }
        } catch (RuntimeException e) {
            log.warn("能力变更事件发布失败（agent 周期轮询兜底）: action={} provider={} cause={}",
                    action, provider, e.getMessage());
        }
    }
}
