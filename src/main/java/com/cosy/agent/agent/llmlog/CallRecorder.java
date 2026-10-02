package com.cosy.agent.agent.llmlog;

import com.cosy.agent.config.LlmCallLogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 大模型调用记录器（旁路，不影响业务调用链）：
 *
 * <ul>
 *   <li>{@link #beginCall}：一次模型调用（含候选降级）开始时创建待完成记录，返回 traceId</li>
 *   <li>{@link #attemptSucceeded}/{@link #attemptFailed}：候选级尝试结果（usage/耗时/失败原因）</li>
 *   <li>{@link #endCall}：组装完整记录入队（异步）</li>
 * </ul>
 *
 * 落库：内存队列 + {@link Scheduled} 定时批量 flush；队列满丢弃并告警（保护内存），
 * 记录失败绝不抛出到业务调用链。Mock 调用不经过本类（短路在 Agent 层）。
 */
@Component
@ConditionalOnProperty(prefix = "cosy.agent.llmlog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CallRecorder {

    private static final Logger log = LoggerFactory.getLogger(CallRecorder.class);

    private final LlmCallLogStore store;
    private final LlmCallLogProperties properties;
    private final LinkedBlockingQueue<LlmCallLog> queue;
    private final Map<String, PendingCall> pending = new ConcurrentHashMap<>();

    public CallRecorder(LlmCallLogStore store, LlmCallLogProperties properties) {
        this.store = store;
        this.properties = properties;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, properties.queueCapacity()));
    }

    // ---- 采集（业务线程调用，零阻塞） ----

    /** 一次调用开始；返回 traceId（null=记录不可用） */
    public String beginCall(String modelChoice, String routeType, List<String> candidateChain,
                            String decisionRationale, String promptContent) {
        try {
            LLMCallContext.Context ctx = LLMCallContext.get();
            String traceId = UUID.randomUUID().toString().replace("-", "");
            PendingCall pc = new PendingCall(traceId, ctx.sessionId(), ctx.taskId(),
                    ctx.iteration(), ctx.toolName(), routeType == null ? "default" : routeType,
                    modelChoice, candidateChain == null ? List.of() : candidateChain,
                    decisionRationale, properties.applyContent(promptContent), Instant.now());
            pending.put(traceId, pc);
            return traceId;
        } catch (RuntimeException e) {
            log.warn("LLM 调用记录 beginCall 失败（跳过记录）: {}", e.getMessage());
            return null;
        }
    }

    /** 候选尝试成功：记录命中模型、usage 与耗时 */
    public void attemptSucceeded(String traceId, String candidate, String responseText, long attemptMs,
                                 Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        PendingCall pc = pending.get(traceId);
        if (pc == null) {
            return;
        }
        pc.chosenModel = candidate;
        pc.responseContent = properties.applyContent(responseText);
        pc.promptTokens = promptTokens;
        pc.completionTokens = completionTokens;
        pc.totalTokens = totalTokens;
        pc.attemptMs = attemptMs;
    }

    /** 候选尝试失败：追加降级原因 */
    public void attemptFailed(String traceId, String candidate, String reason) {
        PendingCall pc = pending.get(traceId);
        if (pc == null) {
            return;
        }
        pc.attempts.add(candidate);
        if (reason != null) {
            pc.reasons.add(reason);
        }
    }

    /** 一次调用结束（全部候选成功/失败）；失败时附聚合错误 */
    public void endCall(String traceId, Instant finishedAt, boolean success, String errorMsg) {
        if (traceId == null) {
            return;
        }
        PendingCall pc = pending.remove(traceId);
        if (pc == null) {
            return;
        }
        // 成功路径已记 chosenModel；失败路径 chosenModel=null（status=FAILED）
        LlmCallLog entry = new LlmCallLog(
                pc.traceId, pc.sessionId, pc.taskId, pc.iteration, pc.toolName,
                pc.routeType, pc.modelChoice, String.join(",", pc.candidateChain),
                String.join(",", pc.attempts),
                pc.reasons.isEmpty() ? null : "[" + String.join(",", pc.reasons) + "]",
                pc.chosenModel,
                success ? LlmCallLog.STATUS_SUCCESS : LlmCallLog.STATUS_FAILED,
                errorMsg, pc.decisionRationale,
                pc.promptContent, pc.responseContent,
                pc.promptTokens, pc.completionTokens, pc.totalTokens,
                java.time.Duration.between(pc.startedAt, finishedAt).toMillis(),
                pc.startedAt, finishedAt);
        if (!queue.offer(entry)) {
            log.warn("LLM 调用记录队列已满（{}），丢弃一条记录: traceId={}", queue.size(), traceId);
        }
    }

    // ---- 落库（定时批量，失败不阻断） ----

    @Scheduled(fixedDelayString = "${cosy.agent.llmlog.flush-interval-ms:5000}")
    public void flush() {
        if (queue.isEmpty()) {
            return;
        }
        List<LlmCallLog> batch = new ArrayList<>();
        queue.drainTo(batch, 500);
        if (!batch.isEmpty()) {
            store.saveAll(batch);
        }
    }

    // ---- 内部 ----

    private static final class PendingCall {
        final String traceId;
        final String sessionId;
        final String taskId;
        final int iteration;
        final String toolName;
        final String routeType;
        final String modelChoice;
        final List<String> candidateChain;
        final List<String> attempts = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final String decisionRationale;
        final String promptContent;
        final Instant startedAt;
        String chosenModel;
        String responseContent;
        Integer promptTokens;
        Integer completionTokens;
        Integer totalTokens;
        long attemptMs;

        PendingCall(String traceId, String sessionId, String taskId, int iteration, String toolName,
                    String routeType, String modelChoice, List<String> candidateChain,
                    String decisionRationale, String promptContent, Instant startedAt) {
            this.traceId = traceId;
            this.sessionId = sessionId;
            this.taskId = taskId;
            this.iteration = iteration;
            this.toolName = toolName;
            this.routeType = routeType;
            this.modelChoice = modelChoice;
            this.candidateChain = candidateChain;
            this.decisionRationale = decisionRationale;
            this.promptContent = promptContent;
            this.startedAt = startedAt;
        }
    }
}
