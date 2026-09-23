/**
 * 容错层（Step 5 启用）。
 *
 * <ul>
 *   <li>LLM 调用：Retry + TimeLimiter + CircuitBreaker</li>
 *   <li>工具调用：TimeLimiter + RateLimiter + Bulkhead</li>
 *   <li>记忆 / 向量存储：Retry + CircuitBreaker</li>
 * </ul>
 *
 * <p>基于 Resilience4j Spring Boot 3 自动装配，通过 application.yml 声明式配置；
 * 降级策略统一返回可读错误并记录审计日志。策略落点矩阵见设计方案第 7 节。</p>
 */
package com.cosy.agent.agent.resilience;
