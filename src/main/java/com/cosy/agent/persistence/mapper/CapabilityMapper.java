package com.cosy.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cosy.agent.persistence.entity.CapabilityEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * capability Mapper（复合主键：capability_name + provider_id）。
 * 仅自定义注解 SQL（MP 单主键能力不适用），查询复用 BaseMapper.selectList。
 */
@Mapper
public interface CapabilityMapper extends BaseMapper<CapabilityEntity> {

    @Insert("""
            INSERT INTO capability
              (capability_name, provider_id, description, parameters_schema, endpoint_path, endpoint_method, retryable, namespace, enabled)
            VALUES (#{capabilityName}, #{providerId}, #{description}, #{parametersSchema},
                    #{endpointPath}, #{endpointMethod}, #{retryable}, #{namespace}, #{enabled})
            ON DUPLICATE KEY UPDATE
              description = VALUES(description), parameters_schema = VALUES(parameters_schema),
              endpoint_path = VALUES(endpoint_path), endpoint_method = VALUES(endpoint_method),
              retryable = VALUES(retryable), namespace = VALUES(namespace),
              enabled = VALUES(enabled)""")
    int upsert(@Param("capabilityName") String capabilityName, @Param("providerId") String providerId,
               @Param("description") String description, @Param("parametersSchema") String parametersSchema,
               @Param("endpointPath") String endpointPath, @Param("endpointMethod") String endpointMethod,
               @Param("retryable") boolean retryable, @Param("namespace") String namespace,
               @Param("enabled") boolean enabled);

    /** 能力启停：仅更新该提供者下某能力的 enabled 列 */
    @Update("UPDATE capability SET enabled = #{enabled} WHERE capability_name = #{capabilityName} AND provider_id = #{providerId}")
    int updateEnabled(@Param("capabilityName") String capabilityName,
                      @Param("providerId") String providerId, @Param("enabled") boolean enabled);
}
