package com.cosy.agent.agent.memory;

import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
import com.cosy.agent.config.AgentProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 Redis 的多层记忆实现（Step 3）。
 *
 * <p>Key 设计：{@code cosy:{level}:{namespace}:{key}}；TTL 分层：
 * WORKING → working-timeout，SESSION → session-timeout，LONG_TERM → long-term-timeout。
 * 条目以 Redis String 存储；列表为前缀键扫描，检索为关键词包含过滤。
 * Step 5 起全部读写落在 MEMORY 容错落点（重试/熔断/超时），故障不雪崩。</p>
 */
@Component
public class RedisMemoryStore implements MemoryStore {

    private static final String KEY_PREFIX = "cosy:";

    private final StringRedisTemplate redis;
    private final AgentProperties properties;
    private final ResilienceSupport resilience;

    public RedisMemoryStore(StringRedisTemplate redis, AgentProperties properties, ResilienceSupport resilience) {
        this.redis = redis;
        this.properties = properties;
        this.resilience = resilience;
    }

    @Override
    public void save(MemoryRecord record) {
        resilience.execute(ResilienceTarget.MEMORY, () -> {
            redis.opsForValue().set(keyFor(record.level(), record.namespace(), record.key()),
                    record.value(), ttlFor(record));
            return null;
        });
    }

    @Override
    public Optional<String> load(MemoryLevel level, String namespace, String key) {
        return resilience.execute(ResilienceTarget.MEMORY,
                () -> Optional.ofNullable(redis.opsForValue().get(keyFor(level, namespace, key))));
    }

    @Override
    public void delete(MemoryLevel level, String namespace, String key) {
        resilience.execute(ResilienceTarget.MEMORY, () -> {
            redis.delete(keyFor(level, namespace, key));
            return null;
        });
    }

    @Override
    public void deleteNamespace(MemoryLevel level, String namespace) {
        resilience.execute(ResilienceTarget.MEMORY, () -> {
            Set<String> keys = redis.keys(keyPrefix(level, namespace) + "*");
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
            return null;
        });
    }

    @Override
    public List<MemoryRecord> list(MemoryLevel level, String namespace) {
        return resilience.execute(ResilienceTarget.MEMORY, () -> {
            String prefix = keyPrefix(level, namespace);
            Set<String> keys = redis.keys(prefix + "*");
            List<MemoryRecord> records = new ArrayList<>(keys.size());
            for (String key : keys) {
                String value = redis.opsForValue().get(key);
                if (value != null) {
                    records.add(new MemoryRecord(level, namespace,
                            key.substring(prefix.length()), value, ttlFor(level), null));
                }
            }
            return records;
        });
    }

    @Override
    public List<MemoryRecord> search(MemoryLevel level, String namespace, String query, int topK) {
        return list(level, namespace).stream()
                .filter(r -> query == null || query.isBlank() || r.value().contains(query))
                .limit(topK)
                .toList();
    }

    private Duration ttlFor(MemoryRecord record) {
        return record.ttl() != null ? record.ttl() : ttlFor(record.level());
    }

    private Duration ttlFor(MemoryLevel level) {
        return switch (level) {
            case WORKING -> properties.workingTimeout();
            case SESSION -> properties.sessionTimeout();
            case LONG_TERM -> properties.longTermTimeout();
        };
    }

    private String keyPrefix(MemoryLevel level, String namespace) {
        return KEY_PREFIX + levelKey(level) + ":" + namespace + ":";
    }

    private String keyFor(MemoryLevel level, String namespace, String key) {
        return keyPrefix(level, namespace) + key;
    }

    /** 层级短键（与设计方案 Key 规范一致：cosy:work / cosy:session / cosy:long） */
    private String levelKey(MemoryLevel level) {
        return switch (level) {
            case WORKING -> "work";
            case SESSION -> "session";
            case LONG_TERM -> "long";
        };
    }
}
