package com.cosy.agent.service;

import com.cosy.agent.agent.core.AgentContext;
import com.cosy.agent.agent.core.AgentMessage;
import com.cosy.agent.agent.core.AgentResult;
import com.cosy.agent.agent.core.AgentState;
import com.cosy.agent.agent.core.ReActAgent;
import com.cosy.agent.agent.task.AgentTask;
import com.cosy.agent.agent.task.TaskStore;
import com.cosy.agent.common.exception.BizException;
import com.cosy.agent.common.enums.ErrorCode;
import com.cosy.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 编排入口（Step 6 起承载任务状态机）：承接请求 → 登记任务（INIT→RUNNING）→
 * 执行 ReAct 循环 → 落终态（COMPLETED / FAILED / TIMEOUT）+ 轨迹审计 → 返回结果。
 *
 * <p>任务/轨迹持久化失败不阻断对话（仅告警，结果照常返回）；
 * 断点恢复（resume）读取历史轨迹注入模型上下文后继续运行，产生新任务。</p>
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final ReActAgent reactAgent;
    private final AgentProperties properties;
    private final TaskStore taskStore;

    public AgentOrchestrator(ReActAgent reactAgent, AgentProperties properties, TaskStore taskStore) {
        this.reactAgent = reactAgent;
        this.properties = properties;
        this.taskStore = taskStore;
    }

    public AgentResult chat(String sessionId, String userId, String input, Boolean mockOverride) {
        AgentTask task = taskStore.createTask(sessionId, userId, input);
        markRunning(task);
        AgentContext context = AgentContext.create(sessionId, userId, properties.maxIterations(), task.taskId(), mockOverride);
        AgentResult result = reactAgent.run(context, input);
        finish(task.taskId(), result);
        return result;
    }

    /** 断点恢复：基于历史任务的轨迹重建上下文，注入历史后继续运行（新任务） */
    public AgentResult resume(String sourceTaskId, String input, Boolean mockOverride) {
        TaskStore.TaskDetail source = taskStore.findById(sourceTaskId)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND, "任务不存在: " + sourceTaskId));
        AgentTask sourceTask = source.task();
        List<AgentMessage> history = source.trace();

        AgentTask task = taskStore.createTask(sourceTask.sessionId(), sourceTask.userId(), input);
        markRunning(task);
        AgentContext context = AgentContext.create(
                sourceTask.sessionId(), sourceTask.userId(), properties.maxIterations(), task.taskId(), mockOverride);
        AgentResult result = reactAgent.run(context, input, history);
        finish(task.taskId(), result);
        return result;
    }

    private void markRunning(AgentTask task) {
        try {
            taskStore.updateTask(task.taskId(), AgentState.RUNNING, null, 0, 0L, null);
        } catch (Exception e) {
            log.warn("任务状态置 RUNNING 失败（持久化降级，不阻断对话）: taskId={}", task.taskId(), e);
        }
    }

    private void finish(String taskId, AgentResult result) {
        try {
            taskStore.updateTask(taskId, result.state(), result.answer(), result.iterations(),
                    result.costMs(), result.errorMessage());
            taskStore.appendTrace(taskId, result.trace());
        } catch (Exception e) {
            log.warn("任务终态/轨迹持久化失败（审计降级，对话结果不受影响）: taskId={}", taskId, e);
        }
    }
}
