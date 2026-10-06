package com.cosy.agent.persistence.config;

import com.cosy.agent.config.VectorProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PG 向量数据源（cosy.agent.vector.store=pgvector 时装配）：
 * PostgreSQL + pgvector 专用连接池（JdbcTemplate 执行向量 SQL，无 MP）。
 *
 * <p>v2 目标：PG 仅保留向量检索；连接管理收口 spring-jdbc（HikariCP 池），
 * 不再出现手写 JDBC 直连代码。</p>
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
        ds.setPoolName("cosy-pg-vector");
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(5_000);
        return ds;
    }

    @Bean
    public JdbcTemplate pgJdbcTemplate(DataSource pgDataSource) {
        return new JdbcTemplate(pgDataSource);
    }
}
