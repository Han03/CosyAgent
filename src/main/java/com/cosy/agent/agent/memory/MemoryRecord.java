package com.cosy.agent.agent.memory;

import java.time.Duration;
import java.time.Instant;

/**
 * 记忆条目：分层存储的最小单元。
 */
public record MemoryRecord(
        MemoryLevel level,
        String sessionId,
        String key,
        String value,
        Duration ttl,
        Instant createdAt) {

    public static MemoryRecord of(MemoryLevel level, String sessionId, String key, String value, Duration ttl) {
        return new MemoryRecord(level, sessionId, key, value, ttl, Instant.now());
    }
}
