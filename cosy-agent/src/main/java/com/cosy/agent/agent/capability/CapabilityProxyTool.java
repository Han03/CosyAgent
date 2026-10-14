package com.cosy.agent.agent.capability;

import com.cosy.agent.agent.tool.AgentTool;

import java.util.Map;

/**
 * 远程能力代理工具（P3.2 轻量版）：仅承载工具元数据（供模型选工具），
 * 执行时经 {@link CapabilityClient#invoke} 委托 capability 服务完成
 * 候选链降级 + 认证注入。由 {@link CapabilitySync} 拉取清单后注册到 ToolRegistry。
 */
public class CapabilityProxyTool implements AgentTool {

    private final CapabilityDefinition definition;
    private final CapabilityClient client;

    public CapabilityProxyTool(CapabilityDefinition definition, CapabilityClient client) {
        this.definition = definition;
        this.client = client;
    }

    @Override
    public String name() {
        return definition.name();
    }

    @Override
    public String description() {
        return definition.description();
    }

    @Override
    public Map<String, String> parameters() {
        return definition.parameters();
    }

    @Override
    public boolean retryable() {
        return definition.retryable();
    }

    @Override
    public Object execute(Map<String, Object> args) {
        return client.invoke(new CapabilityClient.InvokeRequest(name(), args)).data();
    }

    /** 供 ToolRegistry 摘除识别（CapabilitySync diff 用） */
    public String capabilityName() {
        return name();
    }
}
