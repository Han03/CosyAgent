package com.cosy.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cosy.agent.persistence.entity.AgentTraceEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** agent_trace Mapper：批量插入/按任务查走 BaseMapper，幂等查重与会话级联删除走注解 SQL。 */
@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTraceEntity> {

    /** 幂等查重：任务已有轨迹则跳过（防重复落库） */
    @Select("SELECT COUNT(*) FROM agent_trace WHERE task_id = #{taskId}")
    long countByTask(@Param("taskId") String taskId);

    /** 按任务查询（seq 升序） */
    @Select("SELECT * FROM agent_trace WHERE task_id = #{taskId} ORDER BY seq")
    List<AgentTraceEntity> selectByTask(@Param("taskId") String taskId);

    /** 删除会话全部轨迹（task_id 关联） */
    @Delete("DELETE FROM agent_trace WHERE task_id IN (SELECT task_id FROM agent_task WHERE session_id = #{sessionId})")
    int deleteBySession(@Param("sessionId") String sessionId);
}
