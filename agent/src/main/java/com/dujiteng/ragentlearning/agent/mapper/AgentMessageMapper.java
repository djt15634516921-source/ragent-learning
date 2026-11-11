package com.dujiteng.ragentlearning.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dujiteng.ragentlearning.agent.entity.AgentMessageDO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessageDO> {
    @Insert("""
            INSERT INTO t_agent_message
                (conversation_id, user_id, role, content, result_status, tool_calls, sources)
            VALUES
                (#{conversationId}, #{userId}, 'user', #{question}, NULL, '[]'::jsonb, '[]'::jsonb),
                (#{conversationId}, #{userId}, 'assistant', #{answer}, #{status},
                    CAST(#{toolCalls} AS jsonb), CAST(#{sources} AS jsonb))
            """)
    int insertTurn(@Param("userId") long userId, @Param("conversationId") String conversationId,
                   @Param("question") String question, @Param("answer") String answer, @Param("status") String status,
                   @Param("toolCalls") String toolCalls, @Param("sources") String sources);

    @Select("""
            <script>
            SELECT id, conversation_id, user_id, role, content, result_status,
                   CAST(tool_calls AS text) AS tool_calls, CAST(sources AS text) AS sources, created_at
            FROM t_agent_message
            WHERE conversation_id = #{conversationId} AND user_id = #{userId}
            <if test="beforeId != null"> AND id &lt; #{beforeId}</if>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    List<AgentMessageDO> page(@Param("userId") long userId, @Param("conversationId") String conversationId,
                              @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
