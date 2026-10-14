package com.cosy.agent.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Redisson 客户端装配（P0 分布式演进：记忆层由 Lettuce 切换为 Redisson）。
 *
 * <p>配置来源复用 {@code spring.data.redis.*}（host/port/password/database/timeout），
 * 与本项目既有 Redis 配置项完全对齐，无需新增配置段（Boot 4 中属性类为
 * {@link DataRedisProperties}，包路径随模块化迁移至 spring-boot-data-redis）。
 * Bean 标记 {@code @Lazy}：启动时仅创建代理、不建立连接；首次读写记忆时才真正连接，
 * 与旧 Lettuce 的惰性连接语义一致——Redis 不可用时应用照常启动，失败由
 * {@link com.cosy.agent.agent.resilience.ResilienceSupport} 的 MEMORY 落点统一降级。</p>
 *
 * <p>依赖坐标：redisson-spring-boot-starter 4.8.0 + redisson-spring-data-40 4.8.0
 * （版本必须一致，由 Redisson 官方 Spring Boot 兼容矩阵约束）。</p>
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient redissonClient(DataRedisProperties redis) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redis.getHost() + ":" + redis.getPort())
                .setDatabase(redis.getDatabase())
                .setTimeout((int) redis.getTimeout().toMillis());
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            config.useSingleServer().setPassword(redis.getPassword());
        }
        return Redisson.create(config);
    }
}
