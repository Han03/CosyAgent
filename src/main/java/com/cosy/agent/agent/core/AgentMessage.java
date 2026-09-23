package com.cosy.agent.agent.core;

import java.time.Instant;

/**
 * 对话消息单元：ReAct 轨迹（推理链）与多轮对话的统一载体。
 *
 * @param role          消息角色
 * @param content       文本内容（思考 / 回答 / 用户输入 / 工具结果）
 * @param toolCallId    工具调用 ID（Step 2 与 LLM 工具调用对齐）
 * @param toolName      工具名（角色为 TOOL 时有效）
 * @param toolArguments 工具入参（JSON 字符串）
 */
public record AgentMessage(
        Role role,
        String content,
        String toolCallId,
        String toolName,
        String toolArguments,
        Instant timestamp) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public static AgentMessage user(String content) {
        return new AgentMessage(Role.USER, content, null, null, null, Instant.now());
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage(Role.ASSISTANT, content, null, null, null, Instant.now());
    }

    public static AgentMessage tool(String toolCallId, String toolName, String toolArguments, String result) {
        return new AgentMessage(Role.TOOL, result, toolCallId, toolName, toolArguments, Instant.now());
    }
}
