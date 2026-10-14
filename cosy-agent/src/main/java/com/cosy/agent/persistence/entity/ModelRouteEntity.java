package com.cosy.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** model_route 表实体（路由类型 → 有序候选）。复合主键 (route_type, candidate)，走注解 SQL。 */
@TableName("model_route")
public class ModelRouteEntity {

    public String routeType;
    public String candidate;
    public Integer seq;
}
