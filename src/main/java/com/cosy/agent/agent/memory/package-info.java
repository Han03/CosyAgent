/**
 * 记忆层：Redis 多层记忆。
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.agent.memory.MemoryLevel} —— 记忆层级（会话 / 用户长期）</li>
 *   <li>{@link com.cosy.agent.agent.memory.MemoryRecord} —— 记忆记录（层级 + 命名空间 + 键值 + TTL）</li>
 *   <li>{@link com.cosy.agent.agent.memory.MemoryStore} —— 存储接口</li>
 *   <li>{@link com.cosy.agent.agent.memory.RedisMemoryStore} —— Redis 实现（StringRedisTemplate，
 *       键形如 {@code cosy:memory:<level>:<namespace>:<key>}）</li>
 * </ol>
 *
 * <p><b>被依赖</b>：agent.core.DefaultReActAgent 在每轮循环前加载历史记忆、
 * 完成后回写摘要。容错走 ResilienceTarget.MEMORY。</p>
 */
package com.cosy.agent.agent.memory;
