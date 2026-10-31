package com.dujiteng.ragentlearning.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dujiteng.ragentlearning.chat.entity.ChatConversationDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationMapper extends BaseMapper<ChatConversationDO> {
    @Insert("INSERT INTO chat_conversation (conversation_id, user_id) VALUES (#{conversationId}, #{userId}) ON CONFLICT (conversation_id) DO NOTHING")
    int createIfAbsent(@Param("userId") long userId, @Param("conversationId") String conversationId);
}
