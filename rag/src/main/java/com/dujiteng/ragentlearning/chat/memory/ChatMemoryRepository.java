package com.dujiteng.ragentlearning.chat.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dujiteng.ragentlearning.chat.entity.ChatConversationDO;
import com.dujiteng.ragentlearning.chat.mapper.ConversationMapper;
import com.dujiteng.ragentlearning.chat.mapper.MessageMapper;
import com.dujiteng.ragentlearning.chat.vo.ChatMessageVO;
import com.dujiteng.ragentlearning.chat.vo.ConversationVO;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 会话归属与完整轮次写入的事务边界；SQL 由 Mapper 执行。 */
@Repository
public class ChatMemoryRepository {
    private final ObjectMapper json = new ObjectMapper();
    private final ConversationMapper conversations;
    private final MessageMapper messages;
    public ChatMemoryRepository(ConversationMapper conversations, MessageMapper messages) {
        this.conversations = conversations;
        this.messages = messages;
    }
    @Transactional
    public void ensureOwned(long userId, String conversationId, Long knowledgeBaseId) {
        conversations.createIfAbsent(userId, conversationId, knowledgeBaseId);
        requireOwner(userId, conversationId);
        if (!Objects.equals(conversations.selectById(conversationId).getKnowledgeBaseId(), knowledgeBaseId)) {
            throw new ChatApiException(409, "CONVERSATION_KNOWLEDGE_MISMATCH", "此会话已固定知识库或普通聊天模式，请新建会话再切换");
        }
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
            .map(c -> new ConversationVO(c.getConversationId(), c.getCreatedAt().toString(), c.getKnowledgeBaseId())).toList();
    }
    @Transactional
    public void appendTurn(long userId, String conversationId, String question, String answer, List<RetrievedSourceVO> sources) {
        requireOwner(userId, conversationId);
        if (messages.insertOwned(userId, conversationId, "user", question, "[]") != 1) throw forbidden();
        if (messages.insertOwned(userId, conversationId, "assistant", answer, json.writeValueAsString(sources)) != 1) throw forbidden();
    }
    public List<ChatMessageVO> loadRecentHistory(long userId, String conversationId, int maximum) {
        requireOwner(userId, conversationId);
        return messages.recent(userId, conversationId, maximum).stream().map(m -> {
            try {
                List<RetrievedSourceVO> sources = json.readValue(m.getSourcesJson(), json.getTypeFactory()
                        .constructCollectionType(List.class, RetrievedSourceVO.class));
                return new ChatMessageVO(m.getRole(), m.getContent(), sources);
            } catch (RuntimeException error) {
                throw new ChatApiException(503, "CHAT_SOURCES_INVALID", "历史来源数据无法读取，请检查数据库", error);
            }
        }).toList();
    }
    private static ChatApiException forbidden() {
        // 不区分他人资源和旧的无归属记录，避免泄露资源是否存在。
        return new ChatApiException(404, "CONVERSATION_NOT_FOUND", "会话不存在或不属于当前用户");
    }
}
