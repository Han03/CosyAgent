/**
 * 任务状态机与持久化（Step 6）。
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.agent.task.AgentTask} —— 任务记录（状态机字段）</li>
 *   <li>{@link com.cosy.agent.agent.task.TaskStore} —— 存储接口（含 SessionSummary / TaskDetail）</li>
 *   <li>{@link com.cosy.agent.agent.task.InMemoryTaskStore} —— 内存实现（默认）</li>
 *   <li>{@link com.cosy.agent.agent.task.JdbcTaskStore} / {@link com.cosy.agent.agent.task.MysqlJdbcTaskStore}
 *       —— PostgreSQL / MySQL 持久化实现（条件装配，原生 JDBC）</li>
 * </ol>
 *
 * <p><b>被依赖</b>：service.AgentOrchestrator 创建任务、续跑（resume）、
 * 会话消息读写、置顶 / 重命名 / 删除。容错走 ResilienceTarget.TASK。</p>
 */
package com.cosy.agent.agent.task;
