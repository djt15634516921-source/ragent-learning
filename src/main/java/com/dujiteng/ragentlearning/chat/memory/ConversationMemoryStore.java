package com.dujiteng.ragentlearning.chat.memory;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** 数据库保存消息和拥有者；Map 为同一用户的同一会话保存本机锁对象。 */
@Component
public class ConversationMemoryStore {
    private static final int MAX_TURNS = 6;
    private static final System.Logger LOG = System.getLogger(ConversationMemoryStore.class.getName());
    private final ChatMemoryRepository repository;
    private final Map<ConversationKey, Conversation> conversations = new ConcurrentHashMap<>();

    public ConversationMemoryStore(ChatMemoryRepository repository) { this.repository = repository; }

    public Conversation getOrCreate(long userId, String conversationId) {
        try {
            repository.ensureOwned(userId, conversationId);
        } catch (DataAccessException | TransactionException error) {
            throw databaseFailure("CHAT_CONVERSATION_FAILED", "检查或创建会话失败，请检查数据库连接", error);
        }
        return conversations.computeIfAbsent(new ConversationKey(userId, conversationId),
                key -> new Conversation(key.userId(), key.conversationId(), repository));
    }

    /** 用于显示最近历史；读取接口不创建新会话。 */
    public List<Map<String, String>> history(long userId, String conversationId) {
        try {
            return repository.loadRecentMessages(userId, conversationId, MAX_TURNS * 2);
        } catch (DataAccessException | TransactionException error) {
            throw databaseFailure("CHAT_HISTORY_LOAD_FAILED", "读取聊天历史失败，请检查数据库连接和表结构", error);
        }
    }

    public List<Map<String, String>> listConversations(long userId) {
        try {
            return repository.listConversations(userId);
        } catch (DataAccessException | TransactionException error) {
            throw databaseFailure("CHAT_HISTORY_LOAD_FAILED", "读取会话列表失败，请检查数据库连接", error);
        }
    }

    private record ConversationKey(long userId, String conversationId) { }

    public static final class Conversation {
        private final long userId;
        private final String conversationId;
        private final ChatMemoryRepository repository;

        private Conversation(long userId, String conversationId, ChatMemoryRepository repository) {
            this.userId = userId;
            this.conversationId = conversationId;
            this.repository = repository;
        }

        public synchronized List<Map<String, String>> history() {
            try {
                return repository.loadRecentMessages(userId, conversationId, MAX_TURNS * 2);
            } catch (DataAccessException | TransactionException error) {
                throw databaseFailure("CHAT_HISTORY_LOAD_FAILED", "读取聊天历史失败，请检查数据库连接和表结构", error);
            }
        }

        public synchronized void appendTurn(String question, String answer) {
            try {
                repository.appendTurn(userId, conversationId, question, answer);
            } catch (DataAccessException | TransactionException error) {
                throw databaseFailure("CHAT_HISTORY_SAVE_FAILED", "保存聊天记录失败，请检查数据库并确认记录后再重试", error);
            }
        }
    }

    private static ChatApiException databaseFailure(String code, String message, RuntimeException error) {
        LOG.log(System.Logger.Level.ERROR, "聊天数据库访问失败，异常类型：{0}", error.getClass().getName());
        return new ChatApiException(503, code, message, error);
    }
}
