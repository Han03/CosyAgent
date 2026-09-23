package com.cosy.agent.controller;

import com.cosy.agent.agent.core.AgentResult;
import com.cosy.agent.agent.task.AgentTask;
import com.cosy.agent.agent.task.TaskStore;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.common.api.Result;
import com.cosy.agent.service.AgentOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 对外 HTTP 接口（Step 6 起新增任务查询/审计/断点恢复）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final ToolRegistry toolRegistry;
    private final TaskStore taskStore;

    public AgentController(AgentOrchestrator orchestrator, ToolRegistry toolRegistry, TaskStore taskStore) {
        this.orchestrator = orchestrator;
        this.toolRegistry = toolRegistry;
        this.taskStore = taskStore;
    }

    /** 对话入口（Step 2 起返回真实 Agent 回答；Step 6 起 data.taskId 为持久化任务 ID） */
    @PostMapping("/chat")
    public Result<AgentResult> chat(@Valid @RequestBody ChatRequest request) {
        return Result.ok(orchestrator.chat(request.sessionId(), "anonymous", request.message()));
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

    /** 断点恢复：以历史任务轨迹为上下文继续执行（新任务，Step 6） */
    @PostMapping("/tasks/{taskId}/resume")
    public Result<AgentResult> resume(@PathVariable String taskId, @Valid @RequestBody ResumeRequest request) {
        return Result.ok(orchestrator.resume(taskId, request.message()));
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

    public record ChatRequest(
            @NotBlank(message = "sessionId 不能为空") String sessionId,
            @NotBlank(message = "message 不能为空") String message) {
    }

    public record ResumeRequest(
            @NotBlank(message = "message 不能为空") String message) {
    }
}
