package com.dujiteng.ragentlearning.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dujiteng.ragentlearning.agent.entity.AgentConversationDO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversationDO> {
    @Select("""
            SELECT id, user_id, knowledge_base_id, title, revision, created_at, updated_at
            FROM t_agent_conversation WHERE id = #{id} AND user_id = #{userId}
            """)
    AgentConversationDO findOwned(@Param("userId") long userId, @Param("id") String id);

    @Select("""
            <script>
            SELECT id, user_id, knowledge_base_id, title, revision, created_at, updated_at
            FROM t_agent_conversation WHERE user_id = #{userId}
            <if test="knowledgeBaseId != null"> AND knowledge_base_id = #{knowledgeBaseId}</if>
            ORDER BY updated_at DESC, id DESC
            </script>
            """)
    List<AgentConversationDO> listOwned(@Param("userId") long userId, @Param("knowledgeBaseId") Long knowledgeBaseId);

    @Update("""
            UPDATE t_agent_conversation AS c
            SET revision = revision + 1, title = COALESCE(title, #{initialTitle}), updated_at = CURRENT_TIMESTAMP
            WHERE c.id = #{id} AND c.user_id = #{userId} AND c.revision = #{revision}
              AND EXISTS (SELECT 1 FROM knowledge_base k
                          WHERE k.id = c.knowledge_base_id AND k.user_id = c.user_id AND k.deleted_at IS NULL)
            """)
    int advance(@Param("userId") long userId, @Param("id") String id, @Param("revision") long revision,
                @Param("initialTitle") String initialTitle);

    @Delete("DELETE FROM t_agent_conversation WHERE id = #{id} AND user_id = #{userId}")
    int deleteOwned(@Param("userId") long userId, @Param("id") String id);
}
