package com.cosy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 任务持久化配置（cosy.agent.task.*，环境变量可覆盖）。
 *
 * @param store 存储实现：memory（内存，默认，无外部依赖，重启即失）| pg（PostgreSQL 生产形态）
 * @param pg    PostgreSQL 连接参数（store=pg 时生效，原生 JDBC，不触发 DataSource 自动配置）
 */
@ConfigurationProperties(prefix = "cosy.agent.task")
public record TaskProperties(
        @DefaultValue("memory") String store,
        Pg pg) {

    public static final Pg DEFAULT_PG = new Pg("jdbc:postgresql://localhost:5432/cosy", "postgres", "postgres");

    public Pg pg() {
        return pg != null ? pg : DEFAULT_PG;
    }

    public record Pg(
            @DefaultValue("jdbc:postgresql://localhost:5432/cosy") String url,
            @DefaultValue("postgres") String username,
            @DefaultValue("postgres") String password) {
    }
}
