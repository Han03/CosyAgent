package com.cosy.agent.agent.capability;

import java.util.List;
import java.util.Map;

/**
 * 能力持久化接口（仅 CP 模式提供者需要落库；AP 全程由 Registry 内存权威管理）。
 *
 * <p>实现：{@link MemoryCapabilityStore}（默认，仅内存镜像）与
 * {@link MysqlCapabilityStore}（store=mysql，落库 + 启动恢复）。</p>
 */
public interface CapabilityStore {

    /**
     * 保存提供者及其全部能力（CP 注册时调用，事务落库）。
     * AP 提供者可跳过（内存权威，不落库）。
     */
    void save(CapabilityProvider provider, List<Capability> capabilities);

    /** 启动加载全部提供者（CP 恢复；mode 为 cp 的条目） */
    List<CapabilityProvider> loadProviders();

    /** 启动加载能力表：能力全名 → 提供者提供的能力定义 */
    Map<String, List<Capability>> loadCapabilities();

    /** 移除提供者及其能力（CP 注销/摘除时调用） */
    void deleteProvider(String providerId);

    /**
     * 能力启停持久化（CP 实例）：仅更新该提供者下某能力的 enabled 列。
     * AP 实例可跳过（内存权威）。默认实现空操作（内存 store 无需落库）。
     */
    default void updateCapabilityEnabled(String providerId, Capability capability) {
        // 内存镜像实现无需落库；MySQL 实现覆写
    }
}
