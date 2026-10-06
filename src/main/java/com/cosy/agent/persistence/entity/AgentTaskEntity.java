package com.cosy.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * agent_task 表实体（任务主记录）。
 * 字段名与列名按 snake_case ↔ camelCase 自动映射（map-underscore-to-camel-case=true）。
 */
@TableName("agent_task")
public class AgentTaskEntity {

    @TableId(value = "task_id", type = IdType.INPUT)
    public String taskId;
    public String sessionId;
    public String userId;
    public String state;
    public String input;
    public String output;
    public Integer iterations;
    public Long costMs;
    public String errorMessage;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant finishedAt;
    public Boolean pinned;
    public String titleOverride;
}
