package com.cosy.knowledge.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PG 连接池（仅 store=pgvector 时装配）：
 * 独立 Hikari 数据源 + {@code pgJdbcTemplate}，不与任何业务 DataSource 共享；
 * 未安装 PostgreSQL 时应用默认 memory 模式，本配置不触发。
 */
@Configuration
@ConditionalOnProperty(prefix = "cosy.agent.vector", name = "store", havingValue = "pgvector")
public class PgJdbcConfig {

    @Bean
    public DataSource pgDataSource(VectorProperties properties) {
        VectorProperties.Pg pg = properties.pg();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pg.url());
        ds.setUsername(pg.username());
        ds.setPassword(pg.password());
        ds.setMaximumPoolSize(5);
        return ds;
    }

    @Bean
    public JdbcTemplate pgJdbcTemplate(DataSource pgDataSource) {
        return new JdbcTemplate(pgDataSource);
    }
}
