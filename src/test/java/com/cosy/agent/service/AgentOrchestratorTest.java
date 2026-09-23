package com.cosy.agent.service;

import com.cosy.agent.agent.core.AgentContext;
import com.cosy.agent.agent.core.AgentMessage;
import com.cosy.agent.agent.core.AgentResult;
import com.cosy.agent.agent.core.AgentState;
import com.cosy.agent.agent.core.ReActAgent;
import com.cosy.agent.agent.task.AgentTask;
import com.cosy.agent.agent.task.TaskStore;
import com.cosy.agent.common.exception.BizException;
import com.cosy.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编排层任务状态机单测（Step 6）：chat 全链路落库（INIT→RUNNING→终态+轨迹）、
 * 失败任务终态、断点恢复（历史轨迹注入 + 新任务）。
 */
class AgentOrchestratorTest {

    private final TaskStore taskStore = new com.cosy.agent.agent.task.InMemoryTaskStore();
    private final AtomicReference<List<AgentMessage>> lastHistory = new AtomicReference<>();
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ReActAgent agent = new ReActAgent() {
            @Override
            public String name() {
                return "test-agent";
            }

            @Override
            public AgentResult run(AgentContext context, String userInput, List<AgentMessage> history) {
                lastHistory.set(history);
                return new AgentResult(context.sessionId(), "回答:" + userInput, AgentState.COMPLETED,
                        List.of(AgentMessage.user(userInput), AgentMessage.assistant("回答:" + userInput)),
                        1, 10L, null, context.attributes().get("taskId").toString());
            }
        };
        orchestrator = new AgentOrchestrator(agent,
                new AgentProperties(8, Duration.ofSeconds(30), Duration.ofMinutes(30),
                        Duration.ofDays(180), Duration.ofMinutes(10), AgentProperties.Mock.DEFAULT),
                taskStore);
    }

    @Test
    void chatPersistsTaskLifecycleWithTrace() {
        AgentResult result = orchestrator.chat("s1", "u1", "你好", null);

        assertThat(result.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.taskId()).isNotBlank();
        TaskStore.TaskDetail detail = taskStore.findById(result.taskId()).orElseThrow();
        assertThat(detail.task().state()).isEqualTo(AgentState.COMPLETED);
        assertThat(detail.task().input()).isEqualTo("你好");
        assertThat(detail.task().output()).isEqualTo("回答:你好");
        assertThat(detail.task().iterations()).isEqualTo(1);
        assertThat(detail.task().finishedAt()).isNotNull();
        assertThat(detail.trace()).hasSize(2);
        assertThat(taskStore.findBySession("s1", 20)).hasSize(1);
    }

    @Test
    void chatPersistsFailedTask() {
        ReActAgent failing = new ReActAgent() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public AgentResult run(AgentContext context, String userInput, List<AgentMessage> history) {
                return new AgentResult(context.sessionId(), null, AgentState.FAILED,
                        List.of(AgentMessage.user(userInput)), 0, 5L, "模型调用失败",
                        context.attributes().get("taskId").toString());
            }
        };
        AgentOrchestrator failOrchestrator = new AgentOrchestrator(failing,
                new AgentProperties(8, Duration.ofSeconds(30), Duration.ofMinutes(30),
                        Duration.ofDays(180), Duration.ofMinutes(10), AgentProperties.Mock.DEFAULT),
                taskStore);

        AgentResult result = failOrchestrator.chat("s1", "u1", "你好", null);
        TaskStore.TaskDetail detail = taskStore.findById(result.taskId()).orElseThrow();
        assertThat(detail.task().state()).isEqualTo(AgentState.FAILED);
        assertThat(detail.task().errorMessage()).isEqualTo("模型调用失败");
    }

    @Test
    void resumeFeedsHistoryAndCreatesNewTask() {
        AgentResult first = orchestrator.chat("s1", "u1", "第一步", null);

        AgentResult resumed = orchestrator.resume(first.taskId(), "继续", null);

        assertThat(resumed.state()).isEqualTo(AgentState.COMPLETED);
        assertThat(resumed.taskId()).isNotEqualTo(first.taskId()); // 新任务
        assertThat(lastHistory.get()).isNotNull();
        assertThat(lastHistory.get()).hasSize(2); // 历史轨迹注入
        assertThat(taskStore.findById(first.taskId()).orElseThrow().task().state())
                .isEqualTo(AgentState.COMPLETED);
        assertThat(taskStore.findBySession("s1", 20)).hasSize(2);
    }

    @Test
    void resumeRejectsUnknownTask() {
        assertThatThrownBy(() -> orchestrator.resume("task-unknown", "继续", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("任务不存在");
    }

    @Test
    void taskIdSurfacesInAgentResult() {
        AgentResult result = orchestrator.chat("s1", "u1", "你好", null);
        assertThat(result.taskId()).isNotBlank();
    }
}
