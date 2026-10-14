package com.cosy.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关路由（Java DSL，避免 2025.x YAML 配置前缀迁移差异）。
 *
 * <p>路由优先级（声明序即匹配序）：
 * {@code /api/agent/knowledge/**} → {@code lb://cosy-knowledge}（P3.1）；
 * 能力中心三通道 → {@code lb://cosy-capability}（P3.2）：
 * 外部项目注册/心跳/注销 {@code /api/capabilities/**}、客户端能力中心目录管理
 * {@code /api/agent/capabilities/**}、agent 消费端点 {@code /api/agent/capability/**}；
 * 其余 {@code /api/**} → {@code lb://cosy-agent}：经 Nacos 负载均衡寻址；
 * 透传 X-Cosy-* 业务头（模型路由/会话/流式等由下游 agent 处理）。</p>
 */
@Configuration
public class GatewayRoutes {

    @Bean
    public RouteLocator cosyAgentRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("cosy-knowledge", r -> r.path("/api/agent/knowledge/**")
                        .uri("lb://cosy-knowledge"))
                .route("cosy-capability", r -> r.path("/api/capabilities/**", "/api/agent/capabilities/**", "/api/agent/capability/**")
                        .uri("lb://cosy-capability"))
                .route("cosy-agent", r -> r.path("/api/**")
                        .uri("lb://cosy-agent"))
                .build();
    }
}
