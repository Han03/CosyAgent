package com.cosy.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cosy.agent.persistence.entity.CapabilityProviderEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

/** capability_provider Mapper：注册/更新走 upsert（ON DUPLICATE KEY UPDATE，保留原语义）。 */
@Mapper
public interface CapabilityProviderMapper extends BaseMapper<CapabilityProviderEntity> {

    @Insert("""
            INSERT INTO capability_provider
              (provider_id, app_name, base_url, auth_type, auth_model, auth_header_name,
               auth_param_name, auth_value_encrypted, source, call_token, mode, status, last_beat_at, created_at)
            VALUES (#{providerId}, #{appName}, #{baseUrl}, #{authType}, #{authModel}, #{authHeaderName},
                    #{authParamName}, #{authValueEncrypted}, #{source}, #{callToken}, #{mode}, #{status},
                    #{lastBeatAt}, #{createdAt})
            ON DUPLICATE KEY UPDATE
              app_name = VALUES(app_name), base_url = VALUES(base_url),
              auth_type = VALUES(auth_type), auth_model = VALUES(auth_model),
              auth_header_name = VALUES(auth_header_name), auth_param_name = VALUES(auth_param_name),
              auth_value_encrypted = VALUES(auth_value_encrypted), source = VALUES(source),
              call_token = VALUES(call_token),
              mode = VALUES(mode), status = VALUES(status), last_beat_at = VALUES(last_beat_at)""")
    int upsert(@Param("providerId") String providerId, @Param("appName") String appName,
               @Param("baseUrl") String baseUrl, @Param("authType") String authType,
               @Param("authModel") String authModel, @Param("authHeaderName") String authHeaderName,
               @Param("authParamName") String authParamName,
               @Param("authValueEncrypted") String authValueEncrypted, @Param("source") String source,
               @Param("callToken") String callToken, @Param("mode") String mode,
               @Param("status") String status, @Param("lastBeatAt") Timestamp lastBeatAt,
               @Param("createdAt") Timestamp createdAt);
}
