package com.cosy.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * CosyAgent 知识库服务（P3.1）。
 *
 * <p>承载原单体内 knowledge 能力：文档切分 → 确定性向量化 → 向量存储（memory | pgvector）
 * 与检索。对外暴露 {@code /api/agent/knowledge}（upsert / search），由网关
 * 路由 {@code /api/agent/knowledge/** → lb://cosy-knowledge}，客户端路径无感。</p>
 *
 * <p>{@code exclude = DataSourceAutoConfiguration}：数据源由 PgJdbcConfig 条件装配
 * （cosy.agent.vector.store=pgvector 时才建 pg 连接），memory 模式无 DataSource 也不报错。</p>
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class CosyKnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CosyKnowledgeApplication.class, args);
    }
}
