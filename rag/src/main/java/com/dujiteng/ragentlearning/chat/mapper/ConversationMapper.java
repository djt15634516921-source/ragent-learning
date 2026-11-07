package com.dujiteng.ragentlearning.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dujiteng.ragentlearning.chat.entity.ChatConversationDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationMapper extends BaseMapper<ChatConversationDO> {
    @Insert("INSERT INTO chat_conversation (conversation_id, user_id, knowledge_base_id) VALUES (#{conversationId}, #{userId}, #{knowledgeBaseId}) ON CONFLICT (conversation_id) DO NOTHING")
    int createIfAbsent(@Param("userId") long userId, @Param("conversationId") String conversationId, @Param("knowledgeBaseId") Long knowledgeBaseId);

    @Select("SELECT * FROM chat_conversation WHERE conversation_id=#{conversationId} AND user_id=#{userId} AND deleted_at IS NULL FOR UPDATE")
    ChatConversationDO lockOwned(@Param("userId") long userId, @Param("conversationId") String conversationId);

    @Update("UPDATE chat_conversation SET deleted_at=CURRENT_TIMESTAMP WHERE conversation_id=#{conversationId} AND user_id=#{userId} AND deleted_at IS NULL")
    int deleteOwned(@Param("userId") long userId, @Param("conversationId") String conversationId);
}
