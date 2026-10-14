package com.cosy.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cosy.agent.persistence.entity.ModelRouteEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** model_route Mapper（复合主键：route_type + candidate），写走注解 SQL，读走 BaseMapper。 */
@Mapper
public interface ModelRouteMapper extends BaseMapper<ModelRouteEntity> {

    @Insert("""
            INSERT INTO model_route (route_type, candidate, seq)
            VALUES (#{routeType}, #{candidate}, #{seq})""")
    int insertFull(@Param("routeType") String routeType, @Param("candidate") String candidate,
                   @Param("seq") int seq);
}
