package com.cosy.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cosy.agent.persistence.entity.AgentTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * agent_task Mapper：常规 CRUD 走 BaseMapper，会话聚合/终态 CASE 走注解 SQL（保留原 JDBC 语义）。
 */
@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTaskEntity> {

    /**
     * 终态更新：terminal 为真时写 finished_at（CASE WHEN 保留原语义）。
     * 返回更新行数；配合 MpTaskStore 的幂等更新（行不存在 = 会话已删，忽略）。
     */
    @Update("""
            UPDATE agent_task SET state = #{state}, output = #{output}, iterations = #{iterations},
                cost_ms = #{costMs}, error_message = #{errorMessage}, updated_at = #{updatedAt},
                finished_at = CASE WHEN #{terminal} THEN #{now} ELSE finished_at END
            WHERE task_id = #{taskId}""")
    int updateState(@Param("taskId") String taskId, @Param("state") String state,
                    @Param("output") String output, @Param("iterations") int iterations,
                    @Param("costMs") long costMs, @Param("errorMessage") String errorMessage,
                    @Param("terminal") boolean terminal, @Param("updatedAt") java.sql.Timestamp updatedAt,
                    @Param("now") java.sql.Timestamp now);

    /** 会话列表聚合：title=最早任务 title_override 优先，否则最早任务 input；取每会话最新任务行 */
    @Select("""
            SELECT t.session_id,
                   COALESCE(
                     (SELECT t2.title_override FROM agent_task t2
                       WHERE t2.session_id = t.session_id
                         AND t2.title_override IS NOT NULL
                       ORDER BY t2.created_at ASC LIMIT 1),
                     (SELECT t2.input FROM agent_task t2
                       WHERE t2.session_id = t.session_id
                       ORDER BY t2.created_at ASC LIMIT 1)) AS title,
                   t.state, t.updated_at, t.pinned
            FROM agent_task t
            WHERE t.updated_at = (SELECT MAX(t3.updated_at) FROM agent_task t3
                                    WHERE t3.session_id = t.session_id)
            ORDER BY t.pinned DESC, t.updated_at DESC LIMIT #{limit}""")
    List<Map<String, Object>> selectSessionSummaries(@Param("limit") int limit);

    /** 单会话摘要（title 语义同上） */
    @Select("""
            SELECT t.session_id,
                   COALESCE(
                     (SELECT t2.title_override FROM agent_task t2
                       WHERE t2.session_id = t.session_id
                         AND t2.title_override IS NOT NULL
                       ORDER BY t2.created_at ASC LIMIT 1),
                     (SELECT t2.input FROM agent_task t2
                       WHERE t2.session_id = t.session_id
                       ORDER BY t2.created_at ASC LIMIT 1)) AS title,
                   t.state, t.updated_at, t.pinned
            FROM agent_task t
            WHERE t.session_id = #{sessionId}
              AND t.updated_at = (SELECT MAX(t3.updated_at) FROM agent_task t3
                                    WHERE t3.session_id = t.session_id)""")
    Map<String, Object> selectSessionSummary(@Param("sessionId") String sessionId);

    @Update("UPDATE agent_task SET pinned = #{pinned} WHERE session_id = #{sessionId}")
    int updatePinned(@Param("sessionId") String sessionId, @Param("pinned") boolean pinned);

    @Update("UPDATE agent_task SET title_override = #{title} WHERE session_id = #{sessionId}")
    int updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);
}
