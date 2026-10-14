package com.cosy.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cosy.agent.persistence.entity.ModelSpecEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** model_spec Mapper（复合主键：platform_name + model_id），写走注解 SQL，读走 BaseMapper。 */
@Mapper
public interface ModelSpecMapper extends BaseMapper<ModelSpecEntity> {

    @Insert("""
            INSERT INTO model_spec (platform_name, model_id, context_window, capabilities)
            VALUES (#{platformName}, #{modelId}, #{contextWindow}, #{capabilities})""")
    int insertFull(@Param("platformName") String platformName, @Param("modelId") String modelId,
                   @Param("contextWindow") int contextWindow, @Param("capabilities") String capabilities);

    @Select("SELECT * FROM model_spec WHERE platform_name = #{platformName} ORDER BY model_id")
    List<ModelSpecEntity> selectByPlatform(@Param("platformName") String platformName);
}
