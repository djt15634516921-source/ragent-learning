package com.dujiteng.ragentlearning.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dujiteng.ragentlearning.chat.entity.ChatMessageDO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface MessageMapper extends BaseMapper<ChatMessageDO> {
    @Select("""
        SELECT id, role, content, sources_json FROM (
            SELECT m.id, m.role, m.content, m.sources_json FROM chat_message m
            JOIN chat_conversation c ON c.conversation_id = m.conversation_id
            WHERE c.conversation_id = #{conversationId} AND c.user_id = #{userId} AND c.deleted_at IS NULL
            ORDER BY m.id DESC LIMIT #{limit}
        ) recent ORDER BY id ASC
        """)
    List<ChatMessageDO> recent(@Param("userId") long userId, @Param("conversationId") String conversationId, @Param("limit") int limit);
    @Insert("""
        INSERT INTO chat_message (conversation_id, role, content, sources_json)
        SELECT conversation_id, #{role}, #{content}, CAST(#{sources} AS jsonb) FROM chat_conversation
        WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND deleted_at IS NULL
        """)
    int insertOwned(@Param("userId") long userId, @Param("conversationId") String conversationId,
                    @Param("role") String role, @Param("content") String content, @Param("sources") String sources);

    @Select("""
        <script>
        SELECT m.* FROM chat_message m JOIN chat_conversation c ON c.conversation_id=m.conversation_id
        WHERE c.conversation_id=#{conversationId} AND c.user_id=#{userId} AND c.deleted_at IS NULL
        <if test="beforeId != null">AND m.id &lt; #{beforeId}</if>
        ORDER BY m.id DESC LIMIT #{limit}
        </script>
        """)
    List<ChatMessageDO> page(@Param("userId") long userId, @Param("conversationId") String conversationId,
                             @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Delete("DELETE FROM chat_message WHERE conversation_id=#{conversationId}")
    int deleteConversation(@Param("conversationId") String conversationId);
}
