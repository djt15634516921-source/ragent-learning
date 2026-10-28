package com.dujiteng.ragentlearning.chat.memory;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 所有聊天查询和写入都带当前用户；旧的无归属会话不会被访问者自动认领。 */
@Repository
public class ChatMemoryRepository {
    private final JdbcTemplate jdbcTemplate;

    public ChatMemoryRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** 在调模型之前占定会话归属；不同用户抢同一个编号时只允许一个拥有者。 */
    @Transactional
    public void ensureOwned(long userId, String conversationId) {
        jdbcTemplate.update("""
                INSERT INTO chat_conversation (conversation_id, user_id) VALUES (?, ?)
                ON CONFLICT (conversation_id) DO NOTHING
                """, conversationId, userId);
        requireOwner(userId, conversationId);
    }

    public void requireOwner(long userId, String conversationId) {
        List<Long> owners = jdbcTemplate.query("""
                SELECT user_id FROM chat_conversation WHERE conversation_id = ?
                """, (rs, rowNum) -> rs.getObject("user_id", Long.class), conversationId);
        if (owners.isEmpty() || !Long.valueOf(userId).equals(owners.get(0))) {
            throw forbiddenConversation();
        }
    }

    public List<Map<String, String>> loadRecentMessages(long userId, String conversationId, int maxMessages) {
        requireOwner(userId, conversationId);
        List<Map<String, String>> messages = jdbcTemplate.query("""
                SELECT role, content FROM (
                    SELECT m.id, m.role, m.content FROM chat_message m
                    JOIN chat_conversation c ON c.conversation_id = m.conversation_id
                    WHERE c.conversation_id = ? AND c.user_id = ?
                    ORDER BY m.id DESC LIMIT ?
                ) AS recent_messages ORDER BY id ASC
                """, (rs, rowNum) -> Map.of("role", rs.getString("role"), "content", rs.getString("content")),
                conversationId, userId, maxMessages);
        return List.copyOf(messages);
    }

    public List<Map<String, String>> listConversations(long userId) {
        return jdbcTemplate.query("""
                SELECT conversation_id, created_at FROM chat_conversation WHERE user_id = ?
                ORDER BY created_at DESC, conversation_id LIMIT 100
                """, (rs, rowNum) -> Map.of("conversationId", rs.getString("conversation_id"),
                "createdAt", rs.getString("created_at")), userId);
    }

    /** 两条消息共用事务；INSERT 自身也限定拥有者，避免误写他人会话。 */
    @Transactional
    public void appendTurn(long userId, String conversationId, String question, String answer) {
        requireOwner(userId, conversationId);
        insertMessage(userId, conversationId, "user", question);
        insertMessage(userId, conversationId, "assistant", answer);
    }

    private void insertMessage(long userId, String conversationId, String role, String content) {
        int changed = jdbcTemplate.update("""
                INSERT INTO chat_message (conversation_id, role, content)
                SELECT conversation_id, ?, ? FROM chat_conversation
                WHERE conversation_id = ? AND user_id = ?
                """, role, content, conversationId, userId);
        if (changed != 1) throw forbiddenConversation();
    }

    private static ChatApiException forbiddenConversation() {
        // 不区分不存在、属于别人、旧记录尚未绑定；都不泄露消息内容。
        return new ChatApiException(404, "CONVERSATION_NOT_FOUND", "会话不存在或不属于当前用户");
    }
}
