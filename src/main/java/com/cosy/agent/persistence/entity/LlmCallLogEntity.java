package com.cosy.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** llm_call_log 表实体（大模型调用记录，自增主键，开发阶段明文）。 */
@TableName("llm_call_log")
public class LlmCallLogEntity {

    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public String traceId;
    public String sessionId;
    public String taskId;
    public Integer iteration;
    public String toolName;
    public String routeType;
    public String modelChoice;
    public String candidateChain;
    public String injectedTools;
    public String attempts;
    public String reasons;
    public String chosenModel;
    public String status;
    public String errorMsg;
    public String decisionRationale;
    public String promptContent;
    public String rawPrompt;
    public String responseContent;
    public Integer promptTokens;
    public Integer completionTokens;
    public Integer totalTokens;
    public Integer latencyMs;
    public Instant startedAt;
    public Instant finishedAt;
}
