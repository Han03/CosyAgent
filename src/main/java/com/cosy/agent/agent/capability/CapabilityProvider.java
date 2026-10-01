package com.cosy.agent.agent.capability;

import java.time.Instant;

/**
 * 能力提供者（一个外部项目/服务实例）。
 *
 * @param providerId         提供者唯一标识（服务端签发，心跳/注销凭此寻址）
 * @param appName            应用名（如 order-service / open-meteo）
 * @param baseUrl            调用基础地址（协议 + 主机 + 端口）
 * @param authType           调用方鉴权方式（旧字段，固定 shared-secret，语义为
 *                           CosyAgent → 提供者的 callToken 令牌鉴权；保留兼容）
 * @param authModel          第三方认证模型（none | bearer | header | query）；
 *                           none 为默认（兼容现状），其余用于第三方 API 的直接注册
 * @param authHeaderName     authModel=header 时自定义头名（如 X-API-Key）
 * @param authParamName      authModel=query 时 URL 参数名（如 appid）
 * @param authValueEncrypted 第三方认证密钥（AES 加密落库，读取解密，目录掩码）
 * @param source             注册来源：external（外部项目经 /register）| console（前端管理）
 * @param mode               注册模式：ap（临时/心跳续约/超时摘除）| cp（持久/落库/显式注销）
 * @param status             健康状态（UP / SUSPECT / DOWN / UNKNOWN）
 * @param lastBeatAt         最近心跳时间（AP 摘除判定依据）
 * @param callToken          调用令牌：CosyAgent → 提供者 调用时携带鉴权（仅注册响应可见一次）
 * @param createdAt          注册时间
 */
public record CapabilityProvider(
        String providerId,
        String appName,
        String baseUrl,
        String authType,
        String authModel,
        String authHeaderName,
        String authParamName,
        String authValueEncrypted,
        String source,
        String mode,
        CapabilityStatus status,
        Instant lastBeatAt,
        String callToken,
        Instant createdAt) {

    public CapabilityProvider {
        authType = authType == null || authType.isBlank() ? "shared-secret" : authType;
        authModel = authModel == null || authModel.isBlank() ? "none" : authModel;
        source = source == null || source.isBlank() ? "external" : source;
    }

    public boolean isAp() {
        return "ap".equalsIgnoreCase(mode);
    }

    public boolean isCp() {
        return !isAp();
    }
}
