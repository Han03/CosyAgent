package com.cosy.agent.agent.task;

import com.cosy.agent.TestResilience;
import com.cosy.agent.agent.core.AgentMessage;
import com.cosy.agent.agent.core.AgentState;
import com.cosy.agent.config.TaskProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL 业务数据持久化真实集成测试：默认跳过，仅当环境变量 TASK_IT=true
 * 且本地 MySQL（jdbc:mysql://localhost:3306/cosy）可用时执行。
 * 验证 agent_task / agent_trace 表自建、任务/轨迹持久化与查询、幂等追加。
 */
@EnabledIfEnvironmentVariable(named = "TASK_IT", matches = "true")
class MysqlJdbcTaskStoreIntegrationTest {

    private MysqlJdbcTaskStore store;

    @BeforeEach
    void setUp() {
        store = new MysqlJdbcTaskStore(new TaskProperties("mysql", null, new TaskProperties.Mysql(
                System.getenv().getOrDefault("TASK_IT_MYSQL_URL",
                        "jdbc:mysql://localhost:3306/cosy?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"),
                System.getenv().getOrDefault("TASK_IT_MYSQL_USER", "cosy"),
                System.getenv().getOrDefault("TASK_IT_MYSQL_PASSWORD", "cosy"))), TestResilience.defaultResilience());
    }

    @Test
    void persistsTaskAndTraceOnRealMysql() {
        AgentTask task = store.createTask("s-it-mysql", "u1", "查询任务持久化");

        store.updateTask(task.taskId(), AgentState.COMPLETED, "持久化成功", 2, 88L, null);
        store.appendTrace(task.taskId(), List.of(
                AgentMessage.user("查询任务持久化"),
                AgentMessage.assistant("需要调用工具"),
                AgentMessage.tool("c1", "get_server_time", "{}", "{\"time\":\"2026-09-24 10:00:00\"}"),
                AgentMessage.assistant("持久化成功")));

        // 幂等：重复追加不产生重复行
        store.appendTrace(task.taskId(), List.of(AgentMessage.user("查询任务持久化")));

        TaskStore.TaskDetail detail = store.findById(task.taskId()).orElseThrow();
        assertThat(detail.task().state()).isEqualTo(AgentState.COMPLETED);
        assertThat(detail.task().output()).isEqualTo("持久化成功");
        assertThat(detail.task().iterations()).isEqualTo(2);
        assertThat(detail.task().finishedAt()).isNotNull();
        assertThat(detail.trace()).hasSize(4);
        assertThat(detail.trace().get(2).role()).isEqualTo(AgentMessage.Role.TOOL);
        assertThat(detail.trace().get(2).toolName()).isEqualTo("get_server_time");
    }

    @Test
    void findBySessionFiltersBySessionOnRealMysql() {
        AgentTask task = store.createTask("s-it-mysql-list", "u1", "列表");
        store.updateTask(task.taskId(), AgentState.FAILED, null, 1, 10L, "模型调用失败");

        List<AgentTask> tasks = store.findBySession("s-it-mysql-list", 20);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).state()).isEqualTo(AgentState.FAILED);
        assertThat(store.findBySession("s-not-exist", 20)).isEmpty();
        assertThat(store.findById("task-not-exist")).isEmpty();
    }
}
