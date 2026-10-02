package com.cosy.agent.agent.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 自动路由策略持有者：保存运行时策略（YAML 基线初始化 + 管理 API 热更新），
 * 供 {@link AutoRouter} 与各 Resolver 实时读取，避免组件间循环依赖。
 */
public class AutoConfigHolder {

    private static final Logger log = LoggerFactory.getLogger(AutoConfigHolder.class);

    private final AtomicReference<AutoConfig> config;

    public AutoConfigHolder(AutoConfig initial) {
        this.config = new AtomicReference<>(initial == null ? AutoConfig.defaults() : initial);
    }

    public AutoConfig current() {
        return config.get();
    }

    /** 热更新策略（管理 API PUT 后即时生效） */
    public void refresh(AutoConfig updated) {
        AutoConfig next = updated == null ? AutoConfig.defaults() : updated;
        config.set(next);
        log.info("自动路由策略已热更新: resolver={}, rules={}, weights={}",
                next.resolver(), next.rules().size(), next.weights().keySet());
    }
}
