package com.cosy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * 模型路由配置项（prefix: cosy.agent.model-routing）。
 *
 * @param enabled       总开关：true 走模型路由（多平台候选链 + 降级）；false 回退默认单模型
 * @param maxCandidates 单链候选上限（防超长配置）
 * @param store         配置持久化实现：memory（默认，重启回 YAML 基线）| pg | mysql
 * @param platforms     模型平台注册表（name → base-url / api-key）
 * @param routes        路由类型 → 有序候选链（"平台/模型" 列表，降级顺序）
 * @param pg            store=pg 时的连接参数（与 task store 同库同源）
 * @param mysql         store=mysql 时的连接参数
 */
@ConfigurationProperties(prefix = "cosy.agent.model-routing")
public record ModelRoutingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5") int maxCandidates,
        @DefaultValue("memory") String store,
        Map<String, Platform> platforms,
        Map<String, List<String>> routes,
        @NestedConfigurationProperty Pg pg,
        @NestedConfigurationProperty Mysql mysql) {

    public record Platform(String baseUrl, String apiKey) {
    }

    public record Pg(
            @DefaultValue("jdbc:postgresql://localhost:5432/cosy") String url,
            @DefaultValue("postgres") String username,
            @DefaultValue("postgres") String password) {
    }

    public record Mysql(
            @DefaultValue("jdbc:mysql://localhost:3306/cosy?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai") String url,
            @DefaultValue("cosy") String username,
            @DefaultValue("cosy") String password) {
    }
}
