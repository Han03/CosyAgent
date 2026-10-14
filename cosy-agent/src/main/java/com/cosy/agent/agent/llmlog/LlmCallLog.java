package com.cosy.agent.agent.llmlog;

import java.time.Instant;

/**
 * 一次模型调用记录（内存模型，与 llm_call_log 表一列对一列）。
 *
 * @param traceId           调用链路 id（含候选降级，一次 call 一条）
 * @param sessionId         关联会话（来自 LLMCallContext）
 * @param taskId            关联 agent 任务（来自 LLMCallContext）
 * @param iteration         ReAct 轮次
 * @param toolName          工具调用上下文（预留）
 * @param routeType         路由类型（default/reasoning/auto）
 * @param modelChoice       调用方选择（auto 或 平台/模型）
 * @param candidateChain    候选链快照（决策后）
 * @param injectedTools     本轮注入给模型的工具清单（逗号分隔工具名；排查"模型是否看到某工具"）
 * @param attempts          实际尝试序列（逗号分隔）
 * @param reasons           降级原因列表（JSON 数组字符串）
 * @param chosenModel       最终命中（平台/模型；null=全部失败）
 * @param status            SUCCESS | FAILED（单候选失败属降级，状态仍 SUCCESS）
 * @param errorMsg          全部失败时的聚合错误
 * @param decisionRationale 自动路由决策原因（v2.1，决策器输出）
 * @param promptContent     输入内容摘要（开发阶段明文；策略开关可截断/关闭）
 * @param rawPrompt         发给 LLM 的原始提示词（结构化 JSON：messages/tools/model）
 * @param responseContent   输出内容
 * @param promptTokens      SpringAI usage.promptTokens
 * @param completionTokens  usage.completionTokens
 * @param totalTokens       usage.totalTokens
 * @param latencyMs         总耗时（含降级链）
 * @param startedAt         开始时间
 * @param finishedAt        结束时间
 */
public record LlmCallLog(
        String traceId,
        String sessionId,
        String taskId,
        int iteration,
        String toolName,
        String routeType,
        String modelChoice,
        String candidateChain,
        String injectedTools,
        String attempts,
        String reasons,
        String chosenModel,
        String status,
        String errorMsg,
        String decisionRationale,
        String promptContent,
        String rawPrompt,
        String responseContent,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        long latencyMs,
        Instant startedAt,
        Instant finishedAt) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
}
