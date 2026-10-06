package com.cosy.agent.agent.router;

import java.util.Optional;

/**
 * 模型路由配置存储契约：配置持久化抽象。
 *
 * <p>两种实现（随 cosy.agent.persistence 开关）：
 * {@code InMemoryModelRoutingConfigStore}（memory，默认，运行时内存态，重启回 YAML 基线）、
 * {@code MybatisModelRoutingConfigStore}（mysql，表 model_platform / model_spec / model_route，
 * api-key 经 ApiKeyCipher 加密落库）。</p>
 *
 * <p>{@link #load()} 仅当持久化中存在有效配置时返回；为空表示使用 YAML 基线。</p>
 */
public interface ModelRoutingConfigStore {

    /** 读取持久化配置（无则 empty，表示回退 YAML 基线） */
    Optional<RouteConfig> load();

    /** 全量保存配置（覆盖式，立即生效） */
    void save(RouteConfig config);
}
