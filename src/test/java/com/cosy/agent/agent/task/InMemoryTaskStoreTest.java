package com.cosy.agent.agent.task;

import com.cosy.agent.agent.core.AgentMessage;
import com.cosy.agent.agent.core.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存任务存储单测（Step 6）：状态流转、轨迹追加幂等、查询与会话列表。
 */
class InMemoryTaskStoreTest {

    private final InMemoryTaskStore store = new InMemoryTaskStore();

    @Test
    void createTaskStartsWithInitState() {
        AgentTask task = store.createTask("s1", "u1", "你好");
        assertThat(task.taskId()).isNotBlank();
        assertThat(task.state()).isEqualTo(AgentState.INIT);
        assertThat(task.input()).isEqualTo("你好");
        assertThat(task.createdAt()).isNotNull();
    }

    @Test
    void updateTaskTransitionsThroughStateMachine() {
        AgentTask task = store.createTask("s1", "u1", "你好");
        store.updateTask(task.taskId(), AgentState.RUNNING, null, 0, 0L, null);
        store.updateTask(task.taskId(), AgentState.COMPLETED, "回答", 2, 123L, null);

        TaskStore.TaskDetail detail = store.findById(task.taskId()).orElseThrow();
        assertThat(detail.task().state()).isEqualTo(AgentState.COMPLETED);
        assertThat(detail.task().output()).isEqualTo("回答");
        assertThat(detail.task().iterations()).isEqualTo(2);
        assertThat(detail.task().costMs()).isEqualTo(123L);
        assertThat(detail.task().finishedAt()).isNotNull();
    }

    @Test
    void failedTaskKeepsErrorMessage() {
        AgentTask task = store.createTask("s1", "u1", "你好");
        store.updateTask(task.taskId(), AgentState.FAILED, null, 1, 55L, "模型调用失败");
        TaskStore.TaskDetail detail = store.findById(task.taskId()).orElseThrow();
        assertThat(detail.task().state()).isEqualTo(AgentState.FAILED);
        assertThat(detail.task().errorMessage()).isEqualTo("模型调用失败");
    }

    @Test
    void appendTraceIsIdempotentAndPersisted() {
        AgentTask task = store.createTask("s1", "u1", "你好");
        List<AgentMessage> trace = List.of(
                AgentMessage.user("你好"),
                AgentMessage.assistant("回答"));

        store.appendTrace(task.taskId(), trace);
        store.appendTrace(task.taskId(), trace); // 重复调用不重复落库

        TaskStore.TaskDetail detail = store.findById(task.taskId()).orElseThrow();
        assertThat(detail.trace()).hasSize(2);
        assertThat(detail.trace().get(0).content()).isEqualTo("你好");
        assertThat(detail.trace().get(1).content()).isEqualTo("回答");
    }

    @Test
    void findBySessionReturnsTasksNewestFirstWithLimit() {
        AgentTask t1 = store.createTask("s1", "u1", "一");
        AgentTask t2 = store.createTask("s1", "u1", "二");
        store.createTask("s2", "u1", "其他会话");
        store.updateTask(t1.taskId(), AgentState.COMPLETED, "a", 1, 1L, null);
        store.updateTask(t2.taskId(), AgentState.COMPLETED, "b", 1, 1L, null);

        assertThat(store.findBySession("s1", 20)).hasSize(2);
        assertThat(store.findBySession("s1", 1)).hasSize(1);
        assertThat(store.findBySession("不存在", 20)).isEmpty();
    }
}
