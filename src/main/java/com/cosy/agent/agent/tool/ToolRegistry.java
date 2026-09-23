package com.cosy.agent.agent.tool;

import com.cosy.agent.common.enums.ErrorCode;
import com.cosy.agent.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：统一管理 Agent 可用工具，支持注册、查找、列表。
 * 后续可扩展工具白名单、权限校验与调用审计（Step 2 / Step 6）。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<AgentTool> toolBeans) {
        toolBeans.forEach(this::register);
    }

    public void register(AgentTool tool) {
        AgentTool previous = tools.putIfAbsent(tool.name(), tool);
        if (previous != null) {
            throw new IllegalArgumentException("工具名冲突: " + tool.name());
        }
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 按名获取，不存在时抛业务异常 */
    public AgentTool require(String name) {
        return find(name).orElseThrow(() -> new BizException(ErrorCode.TOOL_NOT_FOUND, name));
    }

    public Collection<AgentTool> all() {
        return tools.values();
    }

    public Set<String> names() {
        return tools.keySet();
    }

    public int size() {
        return tools.size();
    }
}
