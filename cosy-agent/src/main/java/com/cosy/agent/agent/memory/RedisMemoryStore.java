package com.cosy.agent.agent.memory;

import com.cosy.agent.agent.resilience.ResilienceSupport;
import com.cosy.agent.agent.resilience.ResilienceTarget;
import com.cosy.agent.config.AgentProperties;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的多层记忆实现（P0 分布式演进：Lettuce/StringRedisTemplate → RedissonClient）。
 *
 * <p>存储结构：每个 {@code level × namespace} 对应一个 RMapCache（Redis Hash），
 * map 名 = {@code cosy:{level}:{namespace}}，字段名 = 记忆 key，逐条目 TTL 分层：
 * WORKING → working-timeout，SESSION → session-timeout，LONG_TERM → long-term-timeout。
 * RMapCache 对过期条目惰性删除（get 返回 null），list/search 与旧实现的
 * "前缀键扫描 + 读取过滤"语义保持一致。</p>
 *
 * <p>全部读写落在 MEMORY 容错落点（重试/熔断/超时），Redis 不可用时按
 * ResilienceTarget.MEMORY 策略降级，故障不雪崩。</p>
 */
@Component
public class RedisMemoryStore implements MemoryStore {

    private final RedissonClient redisson;
    private final AgentProperties properties;
    private final ResilienceSupport resilience;

    public RedisMemoryStore(RedissonClient redisson, AgentProperties properties, ResilienceSupport resilience) {
        this.redisson = redisson;
        this.properties = properties;
        this.resilience = resilience;
    }

    private RMapCache<String, String> mapFor(MemoryLevel level, String namespace) {
        return redisson.getMapCache("cosy:" + levelKey(level) + ":" + namespace);
    }

    @Override
    public void save(MemoryRecord record) {
        resilience.execute(ResilienceTarget.MEMORY, () -> {
            // Redisson 4.x RMapCache 无 Duration 重载，统一转毫秒 + TimeUnit
            mapFor(record.level(), record.namespace())
                    .put(record.key(), record.value(), ttlFor(record).toMillis(), TimeUnit.MILLISECONDS);
            return null;
        });
    }

    @Override
    public Optional<String> load(MemoryLevel level, String namespace, String key) {
        return resilience.execute(ResilienceTarget.MEMORY,
                () -> Optional.ofNullable(mapFor(level, namespace).get(key)));
    }

    @Override
    public void delete(MemoryLevel level, String namespace, String key) {
        resilience.execute(ResilienceTarget.MEMORY, () -> {
            mapFor(level, namespace).remove(key);
            return null;
        });
    }

    @Override
    public void deleteNamespace(MemoryLevel level, String namespace) {
        resilience.execute(ResilienceTarget.MEMORY, () -> {
            RMapCache<String, String> map = mapFor(level, namespace);
            if (!map.isEmpty()) {
                map.clear();
            }
            return null;
        });
    }

    @Override
    public List<MemoryRecord> list(MemoryLevel level, String namespace) {
        return resilience.execute(ResilienceTarget.MEMORY, () -> {
            RMapCache<String, String> map = mapFor(level, namespace);
            // keySet 可能含未及时惰性删除的过期键，逐个 get（过期返回 null）后过滤，
            // 与旧实现 "keys 扫描 + 读取" 的可见性语义一致。
            List<MemoryRecord> records = new ArrayList<>();
            for (String key : map.keySet()) {
                String value = map.get(key);
                if (value != null) {
                    records.add(new MemoryRecord(level, namespace, key, value, ttlFor(level), null));
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

    /** 层级短键（与设计方案 Key 规范一致：cosy:work / cosy:session / cosy:long） */
    private String levelKey(MemoryLevel level) {
        return switch (level) {
            case WORKING -> "work";
            case SESSION -> "session";
            case LONG_TERM -> "long";
        };
    }
}
