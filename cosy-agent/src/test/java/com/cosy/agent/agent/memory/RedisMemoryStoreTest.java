package com.cosy.agent.agent.memory;

import com.cosy.agent.TestResilience;
import com.cosy.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisMemoryStore 单元测试：以 Mock 的 RedissonClient/RMapCache 验证
 * map 命名、TTL 分层与读写/列表/检索/删除逻辑（不依赖真实 Redis）。
 */
class RedisMemoryStoreTest {

    private RedissonClient redisson;
    private RMapCache<String, String> sessionMap;
    private RMapCache<String, String> longMap;
    private RMapCache<String, String> workMap;
    private AgentProperties properties;
    private RedisMemoryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        sessionMap = mock(RMapCache.class);
        longMap = mock(RMapCache.class);
        workMap = mock(RMapCache.class);
        when(redisson.<String, String>getMapCache("cosy:session:s1")).thenReturn(sessionMap);
        when(redisson.<String, String>getMapCache("cosy:long:u1")).thenReturn(longMap);
        when(redisson.<String, String>getMapCache("cosy:work:s1")).thenReturn(workMap);
        properties = new AgentProperties(8, Duration.ofSeconds(30), Duration.ofMinutes(30),
                Duration.ofDays(180), Duration.ofMinutes(10), AgentProperties.Mock.DEFAULT);
        store = new RedisMemoryStore(redisson, properties, TestResilience.defaultResilience());
    }

    @Test
    void saveUsesLevelMapAndDefaultTtl() {
        store.save(MemoryRecord.of(MemoryLevel.SESSION, "s1", "recent", "内容", null));

        verify(sessionMap).put("recent", "内容", properties.sessionTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void saveUsesRecordTtlWhenProvided() {
        store.save(MemoryRecord.of(MemoryLevel.LONG_TERM, "u1", "fact:1", "偏好", Duration.ofHours(1)));

        verify(longMap).put("fact:1", "偏好", Duration.ofHours(1).toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void loadReturnsStoredValue() {
        when(sessionMap.get("recent")).thenReturn("内容");

        assertThat(store.load(MemoryLevel.SESSION, "s1", "recent")).contains("内容");
    }

    @Test
    void listScansMapAndMapsKeys() {
        when(sessionMap.keySet()).thenReturn(Set.of("recent", "state"));
        when(sessionMap.get(anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).endsWith("recent") ? "会话内容" : "COMPLETED");

        List<MemoryRecord> records = store.list(MemoryLevel.SESSION, "s1");

        assertThat(records).hasSize(2);
        assertThat(records).extracting(MemoryRecord::key).containsExactlyInAnyOrder("recent", "state");
        assertThat(records).anyMatch(r -> r.key().equals("recent") && r.value().equals("会话内容"));
    }

    @Test
    void searchFiltersByKeyword() {
        when(longMap.keySet()).thenReturn(Set.of("fact:1", "fact:2"));
        when(longMap.get(anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).endsWith("fact:1") ? "喜欢咖啡" : "喜欢跑步");

        List<MemoryRecord> hits = store.search(MemoryLevel.LONG_TERM, "u1", "咖啡", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).value()).isEqualTo("喜欢咖啡");
    }

    @Test
    void deleteRemovesMapEntry() {
        store.delete(MemoryLevel.WORKING, "s1", "state");

        verify(workMap).remove("state");
    }

    @Test
    void deleteNamespaceClearsMap() {
        store.deleteNamespace(MemoryLevel.WORKING, "s1");

        verify(workMap).clear();
    }
}
