package com.cosy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 知识检索配置（cosy.agent.vector.*，环境变量可覆盖）。
 *
 * @param store     存储实现：memory（内存，默认，无外部依赖）| pgvector（PostgreSQL + PGVector）
 * @param namespace 默认命名空间（业务域/租户隔离）
 * @param topK      检索返回最大命中数
 * @param minScore  最小相似度阈值
 * @param chunkSize 文档切分块大小（字符）
 * @param overlap   切分块重叠（字符）
 * @param pg        PGVector 连接参数（store=pgvector 时生效）
 */
@ConfigurationProperties(prefix = "cosy.agent.vector")
public record VectorProperties(
        @DefaultValue("memory") String store,
        @DefaultValue("default") String namespace,
        @DefaultValue("5") int topK,
        @DefaultValue("0.15") double minScore,
        @DefaultValue("600") int chunkSize,
        @DefaultValue("50") int overlap,
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
