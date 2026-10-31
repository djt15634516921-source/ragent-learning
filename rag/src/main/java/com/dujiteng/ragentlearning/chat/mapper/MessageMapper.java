package com.dujiteng.ragentlearning.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dujiteng.ragentlearning.chat.entity.ChatMessageDO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageMapper extends BaseMapper<ChatMessageDO> {
    @Select("""
        SELECT id, role, content FROM (
            SELECT m.id, m.role, m.content FROM chat_message m
            JOIN chat_conversation c ON c.conversation_id = m.conversation_id
            WHERE c.conversation_id = #{conversationId} AND c.user_id = #{userId}
            ORDER BY m.id DESC LIMIT #{limit}
        ) recent ORDER BY id ASC
        """)
    List<ChatMessageDO> recent(@Param("userId") long userId, @Param("conversationId") String conversationId, @Param("limit") int limit);
    @Insert("""
        INSERT INTO chat_message (conversation_id, role, content)
        SELECT conversation_id, #{role}, #{content} FROM chat_conversation
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
        """)
    int insertOwned(@Param("userId") long userId, @Param("conversationId") String conversationId,
                    @Param("role") String role, @Param("content") String content);
}
