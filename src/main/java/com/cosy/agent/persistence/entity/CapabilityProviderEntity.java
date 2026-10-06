package com.cosy.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** capability_provider 表实体（能力提供者，单主键 provider_id）。 */
@TableName("capability_provider")
public class CapabilityProviderEntity {

    @TableId(value = "provider_id", type = IdType.INPUT)
    public String providerId;
    public String appName;
    public String baseUrl;
    public String authType;
    public String authModel;
    public String authHeaderName;
    public String authParamName;
    public String authValueEncrypted;
    public String source;
    public String callToken;
    public String mode;
    public String status;
    public Instant lastBeatAt;
    public Instant createdAt;
}
