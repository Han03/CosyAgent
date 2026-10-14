package com.cosy.agent.agent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 集成测试：默认跳过，仅当环境变量 REDIS_IT=true 且本地 Redis（localhost:6379）可用时执行。
 * 运行方式：{@code REDIS_IT=true mvn test}
 *
 * <p>P0 改造后底层为 Redisson RMapCache（Redis Hash），key 结构
 * {@code cosy:<level>:<namespace>}（map 名）+ 记忆 key（字段名）。</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true")
class RedisMemoryStoreIntegrationTest {

    @Autowired
    private RedisMemoryStore store;

    @Autowired
    private RedissonClient redisson;

    @AfterEach
    void cleanUp() {
        redisson.getKeys().deleteByPattern("cosy:*");
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

        long ttlMs = redisson.<String, String>getMapCache("cosy:work:it-session").remainTimeToLive("state");
        assertThat(ttlMs).isPositive().isLessThanOrEqualTo(60_000L);
    }

    @Test
    void searchMatchesKeywordOnRealRedis() {
        store.save(MemoryRecord.of(MemoryLevel.LONG_TERM, "it-user", "fact:1", "用户喜欢咖啡", null));
        store.save(MemoryRecord.of(MemoryLevel.LONG_TERM, "it-user", "fact:2", "用户喜欢跑步", null));

        assertThat(store.search(MemoryLevel.LONG_TERM, "it-user", "咖啡", 10)).hasSize(1);
    }
}
