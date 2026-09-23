package com.cosy.agent.agent.tool;

import java.util.Map;

/**
 * 工具契约：Agent 能力的外化载体。
 *
 * <p>Step 2 将把 {@link ToolRegistry} 中的工具桥接到 Spring AI 的
 * ToolCallback（@Tool），供 LLM 自主选择与调用。</p>
 */
public interface AgentTool {

    /** 工具名（唯一，供模型选择） */
    String name();

    /** 工具说明（供模型理解何时调用） */
    String description();

    /** 执行工具，返回可序列化结果 */
    Object execute(Map<String, Object> args);

    /** 工具入参声明（参数名 → JSON Schema 类型），供模型生成参数；默认无参 */
    default Map<String, String> parameters() {
        return Map.of();
    }

    /** 是否幂等可重试（Step 5）：只读/幂等工具返回 true，重试安全；默认 false（不重试） */
    default boolean retryable() {
        return false;
    }
}
