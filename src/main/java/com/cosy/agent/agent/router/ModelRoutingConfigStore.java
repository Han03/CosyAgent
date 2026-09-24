package com.cosy.agent.agent.router;

import java.util.Optional;

/**
 * 模型路由配置存储契约：配置持久化抽象。
 *
 * <p>三种实现（cosy.agent.model-routing.store）：
 * {@code InMemoryModelRoutingConfigStore}（默认，运行时内存态，重启回 YAML 基线）、
 * {@code JdbcModelRoutingConfigStore}（PostgreSQL，表 model_platform / model_route）、
 * {@code MysqlModelRoutingConfigStore}（MySQL，同构表结构）。</p>
 *
 * <p>{@link #load()} 仅当持久化中存在有效配置时返回；为空表示使用 YAML 基线。</p>
 */
public interface ModelRoutingConfigStore {

    /** 读取持久化配置（无则 empty，表示回退 YAML 基线） */
    Optional<RouteConfig> load();

    /** 全量保存配置（覆盖式，立即生效） */
    void save(RouteConfig config);
}
