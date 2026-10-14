package com.cosy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 安全配置（cosy.agent.security.*）。
 *
 * @param apiKey API 访问密钥：非空时启用 X-API-Key 请求头校验（/api/** 生效，actuator 探针放行）；
 *               为空（默认）不启用鉴权——便于本地开发与测试。
 */
@ConfigurationProperties(prefix = "cosy.agent.security")
public record SecurityProperties(
        @DefaultValue("") String apiKey) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
