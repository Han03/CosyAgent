package com.cosy.agent.agent.core;

/**
 * Agent 流式执行事件（SSE 协议：/api/agent/chat/stream）。
 *
 * <p>事件类型：</p>
 * <ul>
 *   <li>{@code thinking}：一轮模型推理开始（index = 迭代轮次）</li>
 *   <li>{@code reasoning}：该轮模型思考文本（content；仅工具调用前有非空文本时发出）</li>
 *   <li>{@code tool}：工具调用开始（toolName / arguments / callId / index）</li>
 *   <li>{@code toolResult}：工具执行完成（content = 观察结果 JSON / durationMs = 执行耗时）</li>
 *   <li>{@code answer}：最终回答文本（COMPLETED 时）</li>
 *   <li>{@code done}：任务终态（sessionId / taskId / state / iterations / costMs）</li>
 *   <li>{@code error}：执行异常（message）</li>
 * </ul>
 */
public record AgentStreamEvent(
        String type,
        Integer index,
        String callId,
        String toolName,
        String arguments,
        String content,
        String sessionId,
        String taskId,
        String state,
        Integer iterations,
        Long costMs,
        String errorMessage,
        Long durationMs) {

    public static AgentStreamEvent thinking(int index) {
        return new AgentStreamEvent("thinking", index, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static AgentStreamEvent reasoning(int index, String content) {
        return new AgentStreamEvent("reasoning", index, null, null, null, content, null, null, null, null, null, null, null);
    }

    public static AgentStreamEvent tool(String callId, String toolName, String arguments, int index) {
        return new AgentStreamEvent("tool", index, callId, toolName, arguments, null, null, null, null, null, null, null, null);
    }

    public static AgentStreamEvent toolResult(String callId, String toolName, String content, Long durationMs) {
        return new AgentStreamEvent("toolResult", null, callId, toolName, null, content, null, null, null, null, null, null, durationMs);
    }

    public static AgentStreamEvent answer(String text) {
        return new AgentStreamEvent("answer", null, null, null, null, text, null, null, null, null, null, null, null);
    }

    public static AgentStreamEvent done(AgentResult result) {
        return new AgentStreamEvent("done", null, null, null, null, result.answer(),
                result.sessionId(), result.taskId(), result.state().name(), result.iterations(),
                result.costMs(), result.errorMessage(), null);
    }

    public static AgentStreamEvent error(String message) {
        return new AgentStreamEvent("error", null, null, null, null, null, null, null, null, null, null, message, null);
    }
}
