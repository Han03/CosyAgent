package com.cosy.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * capability 表实体（能力定义）。
 * <b>复合主键 (capability_name, provider_id)</b>：MP 单主键能力不可用，
 * 全部读写走 Mapper 自定义注解 SQL（见 persistence.mapper.CapabilityMapper）。
 */
@TableName("capability")
public class CapabilityEntity {

    public String capabilityName;
    public String providerId;
    public String description;
    public String parametersSchema;
    public String endpointPath;
    public String endpointMethod;
    public Boolean retryable;
    public String namespace;
    public Boolean enabled;
}
