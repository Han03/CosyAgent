package com.cosy.agent.controller;

import com.cosy.agent.agent.core.AgentMessage;
import com.cosy.agent.agent.core.AgentResult;
import com.cosy.agent.agent.memory.MemoryLevel;
import com.cosy.agent.agent.memory.MemoryStore;
import com.cosy.agent.agent.task.AgentTask;
import com.cosy.agent.agent.task.TaskStore;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.common.api.Result;
import com.cosy.agent.service.AgentOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 对外 HTTP 接口（Step 6 起新增任务查询/审计/断点恢复）。
 *
 * <p>请求头 {@code X-Cosy-Mock}: 请求级 Mock 开关（true/false），覆盖全局
 * {@code cosy.agent.mock.enabled}；不携带时回退全局配置。供客户端设置页动态切换。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final ToolRegistry toolRegistry;
    private final TaskStore taskStore;
    private final MemoryStore memoryStore;

    public AgentController(AgentOrchestrator orchestrator, ToolRegistry toolRegistry, TaskStore taskStore,
                           MemoryStore memoryStore) {
        this.orchestrator = orchestrator;
        this.toolRegistry = toolRegistry;
        this.taskStore = taskStore;
        this.memoryStore = memoryStore;
    }

    /** 对话入口（Step 2 起返回真实 Agent 回答；Step 6 起 data.taskId 为持久化任务 ID） */
    @PostMapping("/chat")
    public Result<AgentResult> chat(@Valid @RequestBody ChatRequest request,
                                    @RequestHeader(value = "X-Cosy-Mock", required = false) String mockHeader) {
        return Result.ok(orchestrator.chat(request.sessionId(), "anonymous", request.message(), parseMock(mockHeader)));
    }

    /** 任务详情（主记录 + 执行轨迹审计，Step 6） */
    @GetMapping("/tasks/{taskId}")
    public Result<TaskStore.TaskDetail> task(@PathVariable String taskId) {
        return Result.ok(taskStore.findById(taskId)
                .orElseThrow(() -> new com.cosy.agent.common.exception.BizException(
                        com.cosy.agent.common.enums.ErrorCode.TASK_NOT_FOUND, taskId)));
    }

    /** 按会话查询任务列表（更新时间倒序，Step 6） */
    @GetMapping("/tasks")
    public Result<List<AgentTask>> tasks(@RequestParam(defaultValue = "20") int limit,
                                         @RequestParam(required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Result.ok(List.of());
        }
        return Result.ok(taskStore.findBySession(sessionId, limit));
    }

    /** 会话列表（会话 = sessionId 分组，title = 首条消息；更新时间倒序） */
    @GetMapping("/sessions")
    public Result<List<TaskStore.SessionSummary>> sessions(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(taskStore.findSessions(Math.max(1, limit)));
    }

    /** 单会话摘要（客户端进入会话恢复标题用） */
    @GetMapping("/sessions/{sessionId}")
    public Result<TaskStore.SessionSummary> session(@PathVariable String sessionId) {
        return Result.ok(taskStore.findSession(sessionId)
                .orElseThrow(() -> new com.cosy.agent.common.exception.BizException(
                        com.cosy.agent.common.enums.ErrorCode.SESSION_NOT_FOUND, sessionId)));
    }

    /** 会话全量消息（进入会话恢复历史用）：按消息时间戳升序合并全部任务轨迹 */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<AgentMessage>> sessionMessages(@PathVariable String sessionId) {
        return Result.ok(taskStore.findMessages(sessionId));
    }

    /** 删除会话：移除全部任务与轨迹，并联动清理 Redis 记忆（work/session 两级） */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Boolean> deleteSession(@PathVariable String sessionId) {
        taskStore.deleteSession(sessionId);
        memoryStore.deleteNamespace(MemoryLevel.WORKING, sessionId);
        memoryStore.deleteNamespace(MemoryLevel.SESSION, sessionId);
        return Result.ok(Boolean.TRUE);
    }

    /** 断点恢复：以历史任务轨迹为上下文继续执行（新任务，Step 6） */
    @PostMapping("/tasks/{taskId}/resume")
    public Result<AgentResult> resume(@PathVariable String taskId, @Valid @RequestBody ResumeRequest request,
                                      @RequestHeader(value = "X-Cosy-Mock", required = false) String mockHeader) {
        return Result.ok(orchestrator.resume(taskId, request.message(), parseMock(mockHeader)));
    }

    /** 已注册工具列表 */
    @GetMapping("/tools")
    public Result<List<String>> tools() {
        return Result.ok(toolRegistry.names().stream().sorted().toList());
    }

    /** 框架状态 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(Map.of(
                "application", "cosy-agent",
                "step", "6",
                "tools", toolRegistry.size()));
    }

    /** 解析 X-Cosy-Mock 请求头：true/1/on → 开；false/0/off → 关；其他/缺失 → 回退全局配置 */
    private Boolean parseMock(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        return switch (header.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "on" -> Boolean.TRUE;
            case "false", "0", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    public record ChatRequest(
            String sessionId,
            @NotBlank(message = "message 不能为空") String message) {
    }

    public record ResumeRequest(
            @NotBlank(message = "message 不能为空") String message) {
    }
}
