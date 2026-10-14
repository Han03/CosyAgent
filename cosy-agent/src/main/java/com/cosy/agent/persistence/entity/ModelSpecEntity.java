package com.cosy.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** model_spec 表实体（平台下模型规格）。复合主键 (platform_name, model_id)，走注解 SQL。 */
@TableName("model_spec")
public class ModelSpecEntity {

    public String platformName;
    public String modelId;
    public Integer contextWindow;
    public String capabilities;
}
