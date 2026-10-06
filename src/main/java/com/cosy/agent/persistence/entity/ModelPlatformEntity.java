package com.cosy.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** model_platform 表实体（模型平台注册项，单主键 platform_name）。 */
@TableName("model_platform")
public class ModelPlatformEntity {

    @TableId(value = "platform_name", type = IdType.INPUT)
    public String platformName;
    public String baseUrl;
    public String apiKey;
    public String type;
    public Integer enabled;
    public Integer timeoutMs;
    public String completionsPath;
}
