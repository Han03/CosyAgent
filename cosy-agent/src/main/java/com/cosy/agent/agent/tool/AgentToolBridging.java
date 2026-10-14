package com.cosy.agent.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentTool → Spring AI 工具桥接：将注册表中的工具转换为模型可理解的
 * {@link ToolCallback}（仅提供 JSON Schema 定义，供 LLM 自主选择工具）。
 *
 * <p>Spring AI 2.0 工具体系：模型侧工具定义统一走 {@link ToolCallback} /
 * {@link ToolDefinition}（OpenAiApi.FunctionTool 已随 2.0 移除）。
 * ChatModel 不再执行工具（内部循环已移除），本实现把
 * {@link AgentTool#name()}/{@link AgentTool#description()}/{@link AgentTool#parameters()}
 * 映射为 JSON Schema，模型返回的 toolCalls 由上层 ReAct 循环统一执行，
 * 因此 {@link ToolCallback#call(String)} 永不触发（抛异常以暴露意外调用）。</p>
 */
public final class AgentToolBridging {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private AgentToolBridging() {
    }

    public static ToolCallback toToolCallback(AgentTool tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", toPropertiesSchema(tool.parameters()));
        schema.put("required", List.of());
        String schemaJson;
        try {
            schemaJson = objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具参数 Schema 序列化失败: " + tool.name(), e);
        }
        ToolDefinition definition = ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(schemaJson)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                // 工具执行归 ReAct 编排层（ToolRegistry），此回调仅承载 schema 注入
                throw new UnsupportedOperationException(
                        "工具由 ReAct 编排层执行，不走 ToolCallback.call: " + tool.name());
            }
        };
    }

    private static Map<String, Object> toPropertiesSchema(Map<String, String> parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        parameters.forEach((name, type) -> properties.put(name, Map.of("type", type)));
        return properties;
    }
}
