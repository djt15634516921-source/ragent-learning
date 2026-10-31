package com.dujiteng.ragentlearning.chat.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dujiteng.ragentlearning.chat.entity.ChatConversationDO;
import com.dujiteng.ragentlearning.chat.mapper.ConversationMapper;
import com.dujiteng.ragentlearning.chat.mapper.MessageMapper;
import com.dujiteng.ragentlearning.chat.vo.ConversationVO;
import com.dujiteng.ragentlearning.common.ChatApiException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 会话归属与完整轮次写入的事务边界；SQL 由 Mapper 执行。 */
@Repository
public class ChatMemoryRepository {
    private final ConversationMapper conversations;
    private final MessageMapper messages;
    public ChatMemoryRepository(ConversationMapper conversations, MessageMapper messages) {
        this.conversations = conversations;
        this.messages = messages;
    }
    @Transactional
    public void ensureOwned(long userId, String conversationId) {
        conversations.createIfAbsent(userId, conversationId);
        requireOwner(userId, conversationId);
    }
    public void requireOwner(long userId, String conversationId) {
        var row = conversations.selectById(conversationId);
        if (row == null || !Long.valueOf(userId).equals(row.getUserId())) {
            throw forbidden();
        }
    }
    public List<Map<String, String>> loadRecentMessages(long userId, String conversationId, int maxMessages) {
        requireOwner(userId, conversationId);
        return messages.recent(userId, conversationId, maxMessages).stream()
            .map(m -> Map.of("role", m.getRole(), "content", m.getContent())).toList();
    }
    public List<ConversationVO> listConversations(long userId) {
        return conversations.selectList(new QueryWrapper<ChatConversationDO>().eq("user_id", userId)
            .orderByDesc("created_at").orderByAsc("conversation_id").last("LIMIT 100")).stream()
            .map(c -> new ConversationVO(c.getConversationId(), c.getCreatedAt().toString())).toList();
    }
    @Transactional
    public void appendTurn(long userId, String conversationId, String question, String answer) {
        requireOwner(userId, conversationId);
        if (messages.insertOwned(userId, conversationId, "user", question) != 1) throw forbidden();
        if (messages.insertOwned(userId, conversationId, "assistant", answer) != 1) throw forbidden();
    }
    private static ChatApiException forbidden() {
        // 不区分他人资源和旧的无归属记录，避免泄露资源是否存在。
        return new ChatApiException(404, "CONVERSATION_NOT_FOUND", "会话不存在或不属于当前用户");
    }
}
