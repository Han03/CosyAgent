package com.cosy.agent.agent.capability;

import com.cosy.agent.agent.tool.AgentTool;

import java.util.List;
import java.util.Map;

/**
 * 远程能力代理工具：把注册中心的一个能力包装成标准 {@link AgentTool}，
 * 注入 ToolRegistry 后对模型与 ReAct 循环完全透明（本地/远程无感知）。
 *
 * <p>执行委托 {@link CapabilityRegistry#call(name, args)}：候选链寻址 → HTTP 调用 →
 * 失败降级。工具定义（名称/描述/参数 Schema）来自能力注册时的声明。</p>
 */
public class CapabilityProxyTool implements AgentTool {

    private final String capabilityName;
    private final CapabilityRegistry registry;

    public CapabilityProxyTool(String capabilityName, CapabilityRegistry registry) {
        this.capabilityName = capabilityName;
        this.registry = registry;
    }

    @Override
    public String name() {
        return capabilityName;
    }

    @Override
    public String description() {
        return registry.describe(capabilityName);
    }

    @Override
    public Object execute(Map<String, Object> args) {
        return registry.call(capabilityName, args == null ? Map.of() : args);
    }

    @Override
    public Map<String, String> parameters() {
        return registry.parametersOf(capabilityName);
    }

    @Override
    public boolean retryable() {
        return registry.retryableOf(capabilityName);
    }

    /** 能力名访问器（ToolRegistry 同步摘除时识别 proxy 用） */
    public String capabilityName() {
        return capabilityName;
    }

    /** 当前提供者基址（健康目录展示用，取候选链首个实例；无实例返回空） */
    public String endpointBase() {
        List<CapabilityRegistry.ActiveCapability> chain = registry.resolveQuietly(capabilityName);
        return chain.isEmpty() ? "" : chain.get(0).provider().baseUrl();
    }
}
