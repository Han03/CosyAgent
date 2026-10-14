package com.cosy.agent.controller;

import com.cosy.agent.agent.llmlog.LlmCallLog;
import com.cosy.agent.agent.llmlog.LlmCallLogStore;
import com.cosy.agent.common.api.Result;
import com.cosy.agent.service.LlmCallLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型调用记录 API（只读，统一 Result 包装）：
 */
@RestController
@RequestMapping("/api/agent/llm-logs")
public class LlmCallLogController {

    private final LlmCallLogService service;

    public LlmCallLogController(LlmCallLogService service) {
        this.service = service;
    }

    @GetMapping
    public Result<Map<String, Object>> page(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String routeType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        LlmCallLogStore.PageResult result = service.page(page, size, sessionId, model, routeType, status,
                toInstant(start), toInstant(end));
        Map<String, Object> data = new HashMap<>();
        data.put("total", result.total());
        data.put("items", result.items());
        return Result.ok(data);
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        Map<String, Object> data = new HashMap<>();
        service.detail(id).ifPresentOrElse(
                logEntry -> data.put("item", logEntry),
                () -> data.put("item", null));
        return Result.ok(data);
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(
            @RequestParam(required = false, defaultValue = "day") String groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String routeType,
            @RequestParam(required = false) String status) {
        List<Map<String, Object>> groups = service.stats(groupBy, toInstant(start), toInstant(end),
                sessionId, model, routeType, status);
        Map<String, Object> data = new HashMap<>();
        data.put("groups", groups);
        return Result.ok(data);
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }
}
