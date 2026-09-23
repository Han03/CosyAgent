package com.cosy.agent.agent.memory;

import com.cosy.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisMemoryStore 单元测试：以 Mock 的 StringRedisTemplate 验证
 * Key 构建、TTL 分层与读写/列表/检索/删除逻辑（不依赖真实 Redis）。
 */
class RedisMemoryStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AgentProperties properties;
    private RedisMemoryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        properties = new AgentProperties(8, Duration.ofSeconds(30), Duration.ofMinutes(30),
                Duration.ofDays(180), Duration.ofMinutes(10), AgentProperties.Mock.DEFAULT);
        store = new RedisMemoryStore(redis, properties);
    }

    @Test
    void saveUsesLevelKeyAndDefaultTtl() {
        store.save(MemoryRecord.of(MemoryLevel.SESSION, "s1", "recent", "内容", null));

        verify(valueOps).set("cosy:session:s1:recent", "内容", properties.sessionTimeout());
    }

    @Test
    void saveUsesRecordTtlWhenProvided() {
        store.save(MemoryRecord.of(MemoryLevel.LONG_TERM, "u1", "fact:1", "偏好", Duration.ofHours(1)));

        verify(valueOps).set("cosy:long:u1:fact:1", "偏好", Duration.ofHours(1));
    }

    @Test
    void loadReturnsStoredValue() {
        when(valueOps.get("cosy:session:s1:recent")).thenReturn("内容");

        assertThat(store.load(MemoryLevel.SESSION, "s1", "recent")).contains("内容");
    }

    @Test
    void listScansPrefixAndMapsKeys() {
        when(redis.keys("cosy:session:s1:*")).thenReturn(Set.of("cosy:session:s1:recent", "cosy:session:s1:state"));
        when(valueOps.get(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).endsWith("recent") ? "会话内容" : "COMPLETED");

        List<MemoryRecord> records = store.list(MemoryLevel.SESSION, "s1");

        assertThat(records).hasSize(2);
        assertThat(records).extracting(MemoryRecord::key).containsExactlyInAnyOrder("recent", "state");
        assertThat(records).anyMatch(r -> r.key().equals("recent") && r.value().equals("会话内容"));
    }

    @Test
    void searchFiltersByKeyword() {
        when(redis.keys("cosy:long:u1:*")).thenReturn(Set.of("cosy:long:u1:fact:1", "cosy:long:u1:fact:2"));
        when(valueOps.get(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).endsWith("fact:1") ? "喜欢咖啡" : "喜欢跑步");

        List<MemoryRecord> hits = store.search(MemoryLevel.LONG_TERM, "u1", "咖啡", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).value()).isEqualTo("喜欢咖啡");
    }

    @Test
    void deleteRemovesKey() {
        store.delete(MemoryLevel.WORKING, "s1", "state");

        verify(redis).delete("cosy:work:s1:state");
    }
}
