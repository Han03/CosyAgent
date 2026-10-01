package com.cosy.agent.agent.capability;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内存能力存储（默认，cosy.agent.capability.store=memory）：
 * 仅内存镜像，不落库（重启即失）。AP 模式全程由 Registry 内存权威管理，本实现仅兜底 CP 语义。
 */
@Component
public class MemoryCapabilityStore implements CapabilityStore {

    @Override
    public void save(CapabilityProvider provider, List<Capability> capabilities) {
        // 内存权威在 Registry，无需镜像
    }

    @Override
    public List<CapabilityProvider> loadProviders() {
        return List.of();
    }

    @Override
    public Map<String, List<Capability>> loadCapabilities() {
        return Map.of();
    }

    @Override
    public void deleteProvider(String providerId) {
        // no-op
    }
}
