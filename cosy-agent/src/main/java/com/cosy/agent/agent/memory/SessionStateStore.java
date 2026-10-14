package com.cosy.agent.agent.memory;

import com.cosy.agent.agent.core.AgentContext;
import com.cosy.agent.agent.core.AgentResult;
import com.cosy.agent.config.AgentProperties;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 会话状态快照（P3.3 分布式收敛）：会话最后一次迭代的上下文快照 → Redisson RMapCache，
 * key {@code cosy:session:{sessionId}}，TTL 随每次写入刷新（默认 30m 对齐会话记忆 TTL）。
 *
 * <p>定位：任务/轨迹的权威持久化在 MySQL（TaskStore）；本快照提供 Redis 侧会话级
 * 「最后一轮状态」镜像（taskId/迭代数/答案摘要/更新时间），供多实例部署时实例切换后
 * 恢复会话上下文、以及会话状态管理查询使用。写入失败仅告警，不阻断对话链路。</p>
 */
@Component
public class SessionStateStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStateStore.class);
    private static final String PREFIX = "cosy:session:";

    private final RedissonClient redisson;
    private final Duration ttl;

    public SessionStateStore(RedissonClient redisson, AgentProperties properties) {
        this.redisson = redisson;
        this.ttl = properties.sessionTimeout();
    }

    /** 写入会话快照：taskId/迭代数/答案摘要/更新时间；TTL 随写入刷新 */
    public void save(AgentContext context, AgentResult result) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("taskId", context.attributes().get("taskId"));
            state.put("userId", context.userId());
            state.put("iterations", result.iterations());
            state.put("answer", result.answer());
            state.put("updatedAt", Instant.now().toString());
            RMapCache<Object, Object> map = redisson.getMapCache(PREFIX + context.sessionId());
            map.putAll(state, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("会话状态快照写入失败（镜像降级，不影响对话）: sessionId={}, cause={}",
                    context.sessionId(), e.getMessage());
        }
    }

    /** 读取会话快照（不存在返回空 Map） */
    public Map<Object, Object> load(String sessionId) {
        try {
            return redisson.<Object, Object>getMapCache(PREFIX + sessionId).readAllMap();
        } catch (RuntimeException e) {
            log.warn("会话状态快照读取失败: sessionId={}, cause={}", sessionId, e.getMessage());
            return Map.of();
        }
    }

    /** 清除会话快照（会话删除时调用） */
    public void clear(String sessionId) {
        try {
            redisson.getMapCache(PREFIX + sessionId).delete();
        } catch (RuntimeException e) {
            log.warn("会话状态快照清除失败: sessionId={}, cause={}", sessionId, e.getMessage());
        }
    }
}
