package com.cosy.agent.agent.router;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存配置存储（默认，cosy.agent.model-routing.store=memory）：
 * 运行时内存态，管理 API 更新后当前进程即时生效；重启回 YAML 基线。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.model-routing", name = "store",
        havingValue = "memory", matchIfMissing = true)
public class InMemoryModelRoutingConfigStore implements ModelRoutingConfigStore {

    private final AtomicReference<RouteConfig> saved = new AtomicReference<>(null);

    @Override
    public Optional<RouteConfig> load() {
        return Optional.ofNullable(saved.get());
    }

    @Override
    public void save(RouteConfig config) {
        saved.set(config);
    }
}
