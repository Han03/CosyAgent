package com.cosy.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** agent_trace 表实体（执行轨迹审计明细，自增主键）。 */
@TableName("agent_trace")
public class AgentTraceEntity {

    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public String taskId;
    public Integer seq;
    public String role;
    public String content;
    public String toolName;
    public String toolArguments;
    public Instant createdAt;
}
