package com.cosy.agent.agent.capability;

import java.time.Instant;

/**
 * 能力提供者（一个外部项目/服务实例）。
 *
 * @param providerId  提供者唯一标识（服务端签发，心跳/注销凭此寻址）
 * @param appName     应用名（如 order-service）
 * @param baseUrl     调用基础地址（协议 + 主机 + 端口）
 * @param authType    鉴权方式（当前 shared-secret）
 * @param mode        注册模式：ap（临时/心跳续约/超时摘除）| cp（持久/落库/显式注销）
 * @param status      健康状态（UP / SUSPECT / DOWN / UNKNOWN）
 * @param lastBeatAt  最近心跳时间（AP 摘除判定依据）
 * @param callToken   调用令牌：CosyAgent → 提供者 调用时携带鉴权（仅注册响应可见一次）
 * @param createdAt   注册时间
 */
public record CapabilityProvider(
        String providerId,
        String appName,
        String baseUrl,
        String authType,
        String mode,
        CapabilityStatus status,
        Instant lastBeatAt,
        String callToken,
        Instant createdAt) {

    public boolean isAp() {
        return "ap".equalsIgnoreCase(mode);
    }

    public boolean isCp() {
        return !isAp();
    }
}
