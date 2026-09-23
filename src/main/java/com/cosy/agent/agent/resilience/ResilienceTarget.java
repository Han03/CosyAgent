package com.cosy.agent.agent.resilience;

/**
 * 容错落点（Step 5）：与设计方案 §7 策略落点矩阵一一对应。
 * 每个落点对应一组 Resilience4j 实例（命名约定 {@code <target>-retry / -cb / -ratelimit / -bulkhead / -timelimiter}）。
 */
public enum ResilienceTarget {
    LLM,      // 模型推理：重试 3 次退避 / 熔断 / 限流 / 超时 60s / 并发隔离
    TOOL,     // 工具调用：幂等工具重试 / 熔断 / 限流 / 超时 30s / 并发隔离
    MEMORY,   // Redis 记忆读写：重试 / 熔断 / 超时 2s
    VECTOR    // 知识库检索：重试 / 熔断 / 超时 5s
}
