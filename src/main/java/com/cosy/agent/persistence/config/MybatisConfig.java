package com.cosy.agent.persistence.config;

import com.cosy.agent.persistence.schema.SchemaMigrator;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * MyBatis-Plus 持久化装配（cosy.agent.persistence=mysql 时生效）：
 * 统一提供 MySQL 数据源（HikariCP 池）+ JdbcTemplate（DDL 迁移用）。
 *
 * <p>MyBatis-Plus 的自动配置（SqlSessionFactory/Mapper）在 classpath 存在
 * {@link DataSource} 单候选时才激活（@ConditionalOnSingleCandidate）——
 * memory 模式下本配置不装配 → 无 DataSource → MyBatis 不初始化，应用照常运行。</p>
 *
 * <p>v2 目标：全工程数据库访问仅剩本数据源 + PG 向量数据源（{@link PgJdbcConfig}）
 * 两条 spring-jdbc 管理的连接池，零手写 JDBC。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "cosy.agent.persistence", name = "store", havingValue = "mysql")
public class MybatisConfig {

    @Bean
    public DataSource mysqlDataSource(PersistenceProperties properties) {
        PersistenceProperties.Mysql mysql = properties.mysql();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(mysql.url());
        ds.setUsername(mysql.username());
        ds.setPassword(mysql.password());
        ds.setPoolName("cosy-mysql");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(5_000);
        return ds;
    }

    @Bean
    public JdbcTemplate mysqlJdbcTemplate(DataSource mysqlDataSource) {
        return new JdbcTemplate(mysqlDataSource);
    }

    /** 建表 DDL 迁移器（ApplicationRunner：启动时幂等执行全部 DDL） */
    @Bean
    public SchemaMigrator schemaMigrator(JdbcTemplate mysqlJdbcTemplate) {
        return new SchemaMigrator(mysqlJdbcTemplate);
    }
}
