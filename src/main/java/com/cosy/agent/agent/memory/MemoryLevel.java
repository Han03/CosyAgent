package com.cosy.agent.agent.memory;

/**
 * Redis 多层记忆分级（Step 3 实现 RedisMemoryStore）。
 */
public enum MemoryLevel {
    /** 工作记忆：当前任务上下文，TTL 最短 */
    WORKING,
    /** 会话记忆：多轮对话摘要与关键信息，TTL 中等 */
    SESSION,
    /** 长期记忆：用户偏好 / 事实画像，TTL 最长，跨会话复用 */
    LONG_TERM
}
