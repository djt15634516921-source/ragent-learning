package com.dujiteng.ragentlearning.agent.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentStateMapper {
    @Insert("""
            INSERT INTO t_agent_state(user_id, session_id, state_key, payload)
            VALUES (#{userId}, #{sessionId}, #{key}, CAST(#{payload} AS jsonb))
            ON CONFLICT (user_id, session_id, state_key)
            DO UPDATE SET payload = EXCLUDED.payload, updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(@Param("userId") long userId, @Param("sessionId") String sessionId,
               @Param("key") String key, @Param("payload") String payload);

    @Select("""
            SELECT CAST(payload AS text) FROM t_agent_state
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND state_key = #{key}
            """)
    String payload(@Param("userId") long userId, @Param("sessionId") String sessionId, @Param("key") String key);

    @Select("SELECT EXISTS(SELECT 1 FROM t_agent_state WHERE user_id = #{userId} AND session_id = #{sessionId})")
    boolean exists(@Param("userId") long userId, @Param("sessionId") String sessionId);

    @Delete("DELETE FROM t_agent_state WHERE user_id = #{userId} AND session_id = #{sessionId}")
    void deleteSession(@Param("userId") long userId, @Param("sessionId") String sessionId);

    @Delete("DELETE FROM t_agent_state WHERE user_id = #{userId} AND session_id = #{sessionId} AND state_key = #{key}")
    void deleteKey(@Param("userId") long userId, @Param("sessionId") String sessionId, @Param("key") String key);

    @Select("SELECT DISTINCT session_id FROM t_agent_state WHERE user_id = #{userId} ORDER BY session_id")
    List<String> listSessions(@Param("userId") long userId);
}
