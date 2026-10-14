package com.cosy.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 网关入口限流（P2-Sentinel）。
 *
 * <p>默认加载一组「按路由 + 按自定义 API 分组」的流控规则，阈值取配置
 * {@code cosy.gateway.sentinel.qps}（默认 100）。规则存于内存；生产可切 Nacos 数据源
 * （spring.cloud.sentinel.datasource.* + rule-type=gw-flow）实现热更新。</p>
 *
 * <p>限流命中由 SCA 的 SentinelGatewayBlockExceptionHandler 统一返回降级 JSON
 * （HTTP 429：Blocked by Sentinel），不会透传到下游 agent。</p>
 */
@Configuration
public class SentinelGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelGatewayConfig.class);

    /** 流控阈值：QPS 上限 */
    private final double qps;

    public SentinelGatewayConfig(@Value("${cosy.gateway.sentinel.qps:100}") double qps) {
        this.qps = qps;
    }

    @PostConstruct
    public void init() {
        // 1) 路由级：cosy-agent 路由整体 QPS 限流
        GatewayFlowRule routeRule = new GatewayFlowRule("cosy-agent")
                .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID)
                .setCount(qps)
                .setIntervalSec(1);
        // 2) API 分组级：对话流式接口单独限流（便于观察/调参）
        ApiDefinition chatApi = new ApiDefinition("cosy-chat-api")
                .setPredicateItems(Set.of(
                        new ApiPathPredicateItem().setPattern("/api/agent/chat/stream")));
        GatewayApiDefinitionManager.loadApiDefinitions(Set.of(chatApi));
        GatewayFlowRule apiRule = new GatewayFlowRule("cosy-chat-api")
                .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME)
                .setCount(qps)
                .setIntervalSec(1);
        GatewayRuleManager.loadRules(Set.of(routeRule, apiRule));
        log.info("[SentinelGateway] 流控规则已加载：路由 cosy-agent QPS={}, API cosy-chat-api QPS={}", qps, qps);
    }
}
