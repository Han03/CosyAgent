package com.cosy.agent.agent.memory;

import java.time.Duration;
import java.time.Instant;

/**
 * 记忆条目：分层存储的最小单元。
 *
 * @param level     记忆层级
 * @param namespace 命名空间（会话记忆用 sessionId，长期记忆用 userId）
 * @param key       记忆键
 * @param value     记忆内容
 * @param ttl       过期时长（null 时使用层级默认 TTL）
 * @param createdAt 创建时间
 */
public record MemoryRecord(
        MemoryLevel level,
        String namespace,
        String key,
        String value,
        Duration ttl,
        Instant createdAt) {

    public static MemoryRecord of(MemoryLevel level, String namespace, String key, String value, Duration ttl) {
        return new MemoryRecord(level, namespace, key, value, ttl, Instant.now());
    }
}
