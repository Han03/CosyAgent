package com.cosy.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cosy.agent.persistence.entity.ModelPlatformEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** model_platform Mapper：常规 CRUD 走 BaseMapper（单主键 platform_name）。 */
@Mapper
public interface ModelPlatformMapper extends BaseMapper<ModelPlatformEntity> {

    @Insert("""
            INSERT INTO model_platform (platform_name, base_url, api_key, type, enabled, timeout_ms, completions_path)
            VALUES (#{platformName}, #{baseUrl}, #{apiKey}, #{type}, #{enabled}, #{timeoutMs}, #{completionsPath})""")
    int insertFull(@Param("platformName") String platformName, @Param("baseUrl") String baseUrl,
                   @Param("apiKey") String apiKey, @Param("type") String type,
                   @Param("enabled") int enabled, @Param("timeoutMs") int timeoutMs,
                   @Param("completionsPath") String completionsPath);
}
