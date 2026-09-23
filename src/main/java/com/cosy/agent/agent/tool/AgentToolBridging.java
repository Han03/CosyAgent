package com.cosy.agent.agent.tool;

import org.springframework.ai.openai.api.OpenAiApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentTool → Spring AI 工具桥接：将注册表中的工具转换为模型可理解的 FunctionTool（JSON Schema）。
 *
 * <p>Step 2 落地：工具契约（ToolRegistry）保持不变，桥接层负责把
 * {@link AgentTool#name()}/{@link AgentTool#description()}/{@link AgentTool#parameters()}
 * 映射为 OpenAI Function Calling 的工具定义，供 LLM 自主选择。</p>
 */
public final class AgentToolBridging {

    private AgentToolBridging() {
    }

    public static OpenAiApi.FunctionTool toFunctionTool(AgentTool tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", toPropertiesSchema(tool.parameters()));
        schema.put("required", List.of());
        OpenAiApi.FunctionTool.Function function =
                new OpenAiApi.FunctionTool.Function(tool.name(), tool.description(), schema, null);
        return new OpenAiApi.FunctionTool(function);
    }

    private static Map<String, Object> toPropertiesSchema(Map<String, String> parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        parameters.forEach((name, type) -> properties.put(name, Map.of("type", type)));
        return properties;
    }
}
