package com.cosy.agent.persistence.store;

import com.cosy.agent.agent.llmlog.LlmCallLog;
import com.cosy.agent.agent.llmlog.LlmCallLogStore;
import com.cosy.agent.persistence.entity.LlmCallLogEntity;
import com.cosy.agent.persistence.mapper.LlmCallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MyBatis-Plus 大模型调用记录存储（cosy.agent.persistence=mysql 时装配，替换 JdbcLlmCallLogStore）：
 * 批量写入 / 分页查询（动态过滤）/ 单条详情 / 聚合统计（Java 侧计算 avg/max/p95/tokens）。
 * 查询异常降级为空结果（不阻断调用链）。
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "cosy.agent.persistence", name = "store", havingValue = "mysql")
public class MybatisLlmCallLogStore implements LlmCallLogStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisLlmCallLogStore.class);

    private final LlmCallLogMapper mapper;

    public MybatisLlmCallLogStore(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void saveAll(List<LlmCallLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        try {
            for (LlmCallLog entry : logs) {
                mapper.insert(toEntity(entry));
            }
        } catch (RuntimeException e) {
            log.warn("LLM 调用记录批量落库失败（丢弃本次 {} 条）: {}", logs.size(), e.getMessage());
        }
    }

    @Override
    public PageResult page(int page, int size, String sessionId, String model,
                           String routeType, String status, Instant start, Instant end) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        Timestamp st = start == null ? null : Timestamp.from(start);
        Timestamp en = end == null ? null : Timestamp.from(end);
        try {
            long total = mapper.countByFilter(sessionId, model, routeType, status, st, en);
            List<LlmCallLog> items = new ArrayList<>();
            for (LlmCallLogEntity e : mapper.selectByFilter(sessionId, model, routeType, status,
                    st, en, safeSize, safePage * safeSize)) {
                items.add(toLog(e));
            }
            return new PageResult(total, items);
        } catch (RuntimeException e) {
            log.warn("LLM 调用记录分页查询失败: {}", e.getMessage());
            return new PageResult(0, List.of());
        }
    }

    @Override
    public Optional<LlmCallLog> findById(long id) {
        try {
            LlmCallLogEntity e = mapper.selectById(id);
            return e == null ? Optional.empty() : Optional.of(toLog(e));
        } catch (RuntimeException e) {
            log.warn("LLM 调用记录详情查询失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Map<String, com.cosy.agent.agent.router.LlmCallStatsProvider.ModelStats> statsLast7Days() {
        Map<String, com.cosy.agent.agent.router.LlmCallStatsProvider.ModelStats> result = new HashMap<>();
        try {
            for (Map<String, Object> row : mapper.statsLast7Days(
                    Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(7))))) {
                long calls = ((Number) row.get("calls")).longValue();
                long success = ((Number) row.get("success")).longValue();
                long degraded = ((Number) row.get("degraded")).longValue();
                result.put((String) row.get("chosen_model"),
                        new com.cosy.agent.agent.router.LlmCallStatsProvider.ModelStats(
                                calls,
                                calls == 0 ? 0 : (double) success / calls,
                                calls == 0 ? 0 : (double) degraded / calls));
            }
        } catch (RuntimeException e) {
            log.debug("LLM 调用统计不可用: {}", e.getMessage());
            return Map.of();
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> aggregate(String groupBy, Instant start, Instant end,
                                               String sessionId, String model,
                                               String routeType, String status) {
        try {
            List<Map<String, Object>> rows = mapper.aggregate(groupBy == null ? "day" : groupBy,
                    Timestamp.from(start), Timestamp.from(end), sessionId, model, routeType, status);
            return computeAggregates(rows);
        } catch (RuntimeException e) {
            log.warn("LLM 调用记录统计查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 聚合计算（保留原 JDBC 版 Java 侧逻辑：avg/max/p95/tokens） */
    private static List<Map<String, Object>> computeAggregates(List<Map<String, Object>> rows) {
        Map<String, List<long[]>> byGroup = new LinkedHashMap<>(); // g → [latency, statusOk, degraded, totalTokens]
        Map<String, long[]> tokens = new HashMap<>(); // g → [prompt, completion, total]
        for (Map<String, Object> row : rows) {
            Object gv = row.get("g");
            String g = gv == null ? "(未知)" : String.valueOf(gv);
            int latency = ((Number) row.get("latency_ms")).intValue();
            boolean ok = "SUCCESS".equals(row.get("status"));
            String reasons = (String) row.get("reasons");
            boolean degraded = ok && reasons != null && !reasons.isBlank();
            int total = ((Number) row.get("total_tokens")).intValue();
            byGroup.computeIfAbsent(g, k -> new ArrayList<>())
                    .add(new long[]{latency, ok ? 1 : 0, degraded ? 1 : 0, total});
            long[] t = tokens.computeIfAbsent(g, k -> new long[3]);
            t[0] += ((Number) row.get("prompt_tokens")).intValue();
            t[1] += ((Number) row.get("completion_tokens")).intValue();
            t[2] += total;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        byGroup.forEach((g, rows0) -> {
            rows0.sort((a, b) -> Long.compare(a[0], b[0]));
            long calls = rows0.size();
            long success = rows0.stream().filter(r -> r[1] == 1).count();
            long degraded = rows0.stream().filter(r -> r[2] == 1).count();
            long avg = (long) rows0.stream().mapToLong(r -> r[0]).average().orElse(0);
            long max = rows0.stream().mapToLong(r -> r[0]).max().orElse(0);
            long p95 = rows0.get((int) Math.min(rows0.size() - 1, Math.ceil(0.95 * rows0.size()) - 1))[0];
            long[] t = tokens.get(g);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("group", g);
            item.put("calls", calls);
            item.put("successRate", calls == 0 ? 0 : Math.round(1000.0 * success / calls) / 10.0);
            item.put("degraded", degraded);
            item.put("avgLatencyMs", avg);
            item.put("maxLatencyMs", max);
            item.put("p95LatencyMs", p95);
            item.put("promptTokens", t[0]);
            item.put("completionTokens", t[1]);
            item.put("totalTokens", t[2]);
            result.add(item);
        });
        return result;
    }

    // ---- 映射 ----

    private static LlmCallLogEntity toEntity(LlmCallLog entry) {
        LlmCallLogEntity e = new LlmCallLogEntity();
        e.traceId = entry.traceId();
        e.sessionId = entry.sessionId();
        e.taskId = entry.taskId();
        e.iteration = entry.iteration();
        e.toolName = entry.toolName();
        e.routeType = entry.routeType();
        e.modelChoice = entry.modelChoice();
        e.candidateChain = truncate(entry.candidateChain(), 512);
        e.injectedTools = truncate(entry.injectedTools(), 512);
        e.attempts = truncate(entry.attempts(), 512);
        e.reasons = truncate(entry.reasons(), 1000);
        e.chosenModel = entry.chosenModel();
        e.status = entry.status();
        e.errorMsg = truncate(entry.errorMsg(), 512);
        e.decisionRationale = truncate(entry.decisionRationale(), 1024);
        e.promptContent = entry.promptContent();
        e.rawPrompt = entry.rawPrompt();
        e.responseContent = entry.responseContent();
        e.promptTokens = entry.promptTokens();
        e.completionTokens = entry.completionTokens();
        e.totalTokens = entry.totalTokens();
        e.latencyMs = (int) Math.min(Integer.MAX_VALUE, entry.latencyMs());
        e.startedAt = entry.startedAt();
        e.finishedAt = entry.finishedAt();
        return e;
    }

    private static LlmCallLog toLog(LlmCallLogEntity e) {
        return new LlmCallLog(e.traceId, e.sessionId, e.taskId,
                e.iteration == null ? 0 : e.iteration, e.toolName, e.routeType, e.modelChoice,
                e.candidateChain, e.injectedTools, e.attempts, e.reasons, e.chosenModel,
                e.status, e.errorMsg, e.decisionRationale, e.promptContent, e.rawPrompt,
                e.responseContent, e.promptTokens, e.completionTokens, e.totalTokens,
                e.latencyMs == null ? 0L : e.latencyMs, e.startedAt, e.finishedAt);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
