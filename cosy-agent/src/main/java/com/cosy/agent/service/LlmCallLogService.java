package com.cosy.agent.service;

import com.cosy.agent.agent.llmlog.LlmCallLog;
import com.cosy.agent.agent.llmlog.LlmCallLogStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 大模型调用记录查询服务：分页列表 / 详情 / 聚合统计。
 * 写入走 {@link com.cosy.agent.agent.llmlog.CallRecorder}（异步），本服务只读。
 */
@Service
public class LlmCallLogService {

    private final LlmCallLogStore store;

    public LlmCallLogService(LlmCallLogStore store) {
        this.store = store;
    }

    public LlmCallLogStore.PageResult page(int page, int size, String sessionId, String model,
                                           String routeType, String status,
                                           Instant start, Instant end) {
        return store.page(page, size, sessionId, model, routeType, status, start, end);
    }

    public Optional<LlmCallLog> detail(long id) {
        return store.findById(id);
    }

    public List<Map<String, Object>> stats(String groupBy, Instant start, Instant end,
                                           String sessionId, String model,
                                           String routeType, String status) {
        Instant s = start == null ? Instant.now().minusSeconds(86400) : start;
        Instant e = end == null ? Instant.now() : end;
        if (s.isAfter(e)) {
            return List.of();
        }
        return store.aggregate(groupBy, s, e, sessionId, model, routeType, status);
    }
}
