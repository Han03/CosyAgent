package com.cosy.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cosy.agent.persistence.entity.LlmCallLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * llm_call_log Mapper：分页（动态 where，<script>）+ 聚合统计（groupBy 白名单映射）。
 * 保留原 JDBC 的查询语义（LIMIT/OFFSET 手写，不用 MP 分页插件，与现状一致）。
 */
@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLogEntity> {

    @Select("""
            <script>
            SELECT COUNT(*) FROM llm_call_log
            <where>
              <if test="sessionId != null and sessionId != ''">AND session_id = #{sessionId}</if>
              <if test="model != null and model != ''">AND chosen_model = #{model}</if>
              <if test="routeType != null and routeType != ''">AND route_type = #{routeType}</if>
              <if test="status != null and status != ''">AND status = #{status}</if>
              <if test="start != null">AND started_at &gt;= #{start}</if>
              <if test="end != null">AND started_at &lt;= #{end}</if>
            </where>
            </script>""")
    long countByFilter(@Param("sessionId") String sessionId, @Param("model") String model,
                       @Param("routeType") String routeType, @Param("status") String status,
                       @Param("start") Timestamp start, @Param("end") Timestamp end);

    @Select("""
            <script>
            SELECT * FROM llm_call_log
            <where>
              <if test="sessionId != null and sessionId != ''">AND session_id = #{sessionId}</if>
              <if test="model != null and model != ''">AND chosen_model = #{model}</if>
              <if test="routeType != null and routeType != ''">AND route_type = #{routeType}</if>
              <if test="status != null and status != ''">AND status = #{status}</if>
              <if test="start != null">AND started_at &gt;= #{start}</if>
              <if test="end != null">AND started_at &lt;= #{end}</if>
            </where>
            ORDER BY started_at DESC LIMIT #{limit} OFFSET #{offset}
            </script>""")
    List<LlmCallLogEntity> selectByFilter(@Param("sessionId") String sessionId, @Param("model") String model,
                                          @Param("routeType") String routeType, @Param("status") String status,
                                          @Param("start") Timestamp start, @Param("end") Timestamp end,
                                          @Param("limit") int limit, @Param("offset") int offset);

    /** 近 7 天按 chosen_model 聚合（L2 可靠性因子数据源） */
    @Select("""
            SELECT chosen_model,
                   COUNT(*) AS calls,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success,
                   SUM(CASE WHEN status = 'SUCCESS' AND reasons IS NOT NULL AND reasons <> ''
                            THEN 1 ELSE 0 END) AS degraded
            FROM llm_call_log
            WHERE started_at >= #{since} AND chosen_model IS NOT NULL AND chosen_model <> ''
            GROUP BY chosen_model""")
    List<Map<String, Object>> statsLast7Days(@Param("since") Timestamp since);

    /**
     * 聚合明细拉取（Java 侧计算 avg/max/p95/tokens，保留原 JDBC 逻辑）。
     * groupBy 白名单：model → chosen_model；routeType → route_type；status → status；其余 → DATE(started_at)。
     */
    @Select("""
            <script>
            SELECT
              <choose>
                <when test="groupBy == 'model'">chosen_model</when>
                <when test="groupBy == 'routeType'">route_type</when>
                <when test="groupBy == 'status'">status</when>
                <otherwise>DATE(started_at)</otherwise>
              </choose> AS g,
              latency_ms, status, reasons, total_tokens, prompt_tokens, completion_tokens
            FROM llm_call_log
            WHERE started_at &gt;= #{start} AND started_at &lt;= #{end}
              <if test="sessionId != null and sessionId != ''">AND session_id = #{sessionId}</if>
              <if test="model != null and model != ''">AND chosen_model = #{model}</if>
              <if test="routeType != null and routeType != ''">AND route_type = #{routeType}</if>
              <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY g, latency_ms
            </script>""")
    List<Map<String, Object>> aggregate(@Param("groupBy") String groupBy, @Param("start") Timestamp start,
                                        @Param("end") Timestamp end, @Param("sessionId") String sessionId,
                                        @Param("model") String model, @Param("routeType") String routeType,
                                        @Param("status") String status);
}
