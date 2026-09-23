package com.cosy.agent.agent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 集成测试：默认跳过，仅当环境变量 REDIS_IT=true 且本地 Redis（localhost:6379）可用时执行。
 * 运行方式：{@code REDIS_IT=true mvn test}
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true")
class RedisMemoryStoreIntegrationTest {

    @Autowired
    private RedisMemoryStore store;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        Set<String> keys = redis.keys("cosy:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void roundTripPersistLoadListDelete() {
        store.save(MemoryRecord.of(MemoryLevel.SESSION, "it-session", "recent", "会话内容", Duration.ofMinutes(5)));

        assertThat(store.load(MemoryLevel.SESSION, "it-session", "recent")).contains("会话内容");

        List<MemoryRecord> records = store.list(MemoryLevel.SESSION, "it-session");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo("recent");

        store.delete(MemoryLevel.SESSION, "it-session", "recent");
        assertThat(store.load(MemoryLevel.SESSION, "it-session", "recent")).isEmpty();
    }

    @Test
    void ttlIsAppliedOnSave() {
        store.save(MemoryRecord.of(MemoryLevel.WORKING, "it-session", "state", "RUNNING", Duration.ofSeconds(60)));

        Long ttl = redis.getExpire("cosy:work:it-session:state");
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(60);
    }

    @Test
    void searchMatchesKeywordOnRealRedis() {
        store.save(MemoryRecord.of(MemoryLevel.LONG_TERM, "it-user", "fact:1", "用户喜欢咖啡", null));
        store.save(MemoryRecord.of(MemoryLevel.LONG_TERM, "it-user", "fact:2", "用户喜欢跑步", null));

        assertThat(store.search(MemoryLevel.LONG_TERM, "it-user", "咖啡", 10)).hasSize(1);
    }
}
