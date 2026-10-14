package com.cosy.agent.agent.mock;

import java.util.Map;

/**
 * 候选动作：模型本轮可能调用的工具。
 *
 * @param toolName          工具名（应为 ToolRegistry 已注册工具；self-heal 剧本刻意使用未注册名）
 * @param argumentTemplate  工具参数模板（Map，值可含 {@code {random}} 占位，引擎生成时替换为随机数）
 */
public record MockAction(String toolName, Map<String, Object> argumentTemplate) {

    public static MockAction of(String toolName) {
        return new MockAction(toolName, Map.of());
    }

    public static MockAction of(String toolName, Map<String, Object> argumentTemplate) {
        return new MockAction(toolName, argumentTemplate);
    }
}
