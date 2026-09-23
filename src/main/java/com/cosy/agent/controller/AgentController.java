package com.cosy.agent.controller;

import com.cosy.agent.agent.core.AgentResult;
import com.cosy.agent.agent.tool.ToolRegistry;
import com.cosy.agent.common.api.Result;
import com.cosy.agent.service.AgentOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 对外 HTTP 接口。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final ToolRegistry toolRegistry;

    public AgentController(AgentOrchestrator orchestrator, ToolRegistry toolRegistry) {
        this.orchestrator = orchestrator;
        this.toolRegistry = toolRegistry;
    }

    /** 对话入口（Step 2 起返回真实 Agent 回答） */
    @PostMapping("/chat")
    public Result<AgentResult> chat(@Valid @RequestBody ChatRequest request) {
        return Result.ok(orchestrator.chat(request.sessionId(), "anonymous", request.message()));
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
                "step", "1",
                "tools", toolRegistry.size()));
    }

    public record ChatRequest(
            @NotBlank(message = "sessionId 不能为空") String sessionId,
            @NotBlank(message = "message 不能为空") String message) {
    }
}
