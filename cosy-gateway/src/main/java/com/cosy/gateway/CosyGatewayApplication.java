package com.cosy.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CosyAgent 统一入口网关（P2）。
 *
 * <p>职责：{@code /api/**} 流量经 {@code lb://cosy-agent} 动态转发到注册在 Nacos 的
 * cosy-agent 实例；统一 API Key 鉴权；Sentinel 入口限流；SSE 长连接透传。</p>
 *
 * <p>端口 8080（客户端无需改 baseUrl）；cosy-agent 实例移入 8090。</p>
 */
@SpringBootApplication
public class CosyGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CosyGatewayApplication.class, args);
    }
}
