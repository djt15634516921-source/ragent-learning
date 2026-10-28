package com.dujiteng.ragentlearning.chat.memory;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 负责 SQL 读写；会话校验、模型调用和流式发送交给上层。 */
@Repository
public class ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 先从末尾取最近的消息，再恢复为从旧到新的顺序。 */
    public List<Map<String, String>> loadRecentMessages(String conversationId, int maxMessages) {
        List<Map<String, String>> messages = jdbcTemplate.query("""
                SELECT role, content
                FROM (
                    SELECT id, role, content
                    FROM chat_message
                    WHERE conversation_id = ?
                    ORDER BY id DESC
                    LIMIT ?
                ) AS recent_messages
                ORDER BY id ASC
                """,
                (rs, rowNum) -> Map.of(
                        "role", rs.getString("role"),
                        "content", rs.getString("content")),
                conversationId, maxMessages);
        return List.copyOf(messages);
    }

    /** 三次写入使用同一个事务；失败时回滚，避免只保存了问题。 */
    @Transactional
    public void appendTurn(String conversationId, String question, String answer) {
        jdbcTemplate.update("""
                INSERT INTO chat_conversation (conversation_id)
                VALUES (?)
                ON CONFLICT (conversation_id) DO NOTHING
                """, conversationId);

        jdbcTemplate.update("""
                INSERT INTO chat_message (conversation_id, role, content)
                VALUES (?, ?, ?)
                """, conversationId, "user", question);

        jdbcTemplate.update("""
                INSERT INTO chat_message (conversation_id, role, content)
                VALUES (?, ?, ?)
                """, conversationId, "assistant", answer);
    }
}
