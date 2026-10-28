package com.dujiteng.ragentlearning.chat.memory;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** 数据库保存消息；本机 Map 保留会话对象，供同一会话的请求共用一把锁。 */
@Component
public class ConversationMemoryStore {

    // 一轮 = 一个 user 问题 + 一个 assistant 回答。只限制发送给模型的历史长度。
    private static final int MAX_TURNS = 6;
    private static final System.Logger LOG = System.getLogger(ConversationMemoryStore.class.getName());

    private final ChatMemoryRepository repository;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public ConversationMemoryStore(ChatMemoryRepository repository) {
        this.repository = repository;
    }

    public Conversation getOrCreate(String conversationId) {
        return conversations.computeIfAbsent(
                conversationId, id -> new Conversation(id, repository));
    }

    /** 保存会话编号与数据库访问对象；消息内容在数据库里。 */
    public static final class Conversation {

        private final String conversationId;
        private final ChatMemoryRepository repository;

        private Conversation(String conversationId, ChatMemoryRepository repository) {
            this.conversationId = conversationId;
            this.repository = repository;
        }

        public synchronized List<Map<String, String>> history() {
            try {
                return repository.loadRecentMessages(conversationId, MAX_TURNS * 2);
            } catch (DataAccessException | TransactionException error) {
                LOG.log(System.Logger.Level.ERROR,
                        "聊天历史读取失败，异常类型：{0}", error.getClass().getName());
                throw new ChatApiException(503, "CHAT_HISTORY_LOAD_FAILED",
                        "读取聊天历史失败，请检查数据库连接和表结构", error);
            }
        }

        public synchronized void appendTurn(String question, String answer) {
            try {
                // 调用 Spring 注入的 Repository；其事务提交成功后，这次调用才正常返回。
                repository.appendTurn(conversationId, question, answer);
            } catch (DataAccessException | TransactionException error) {
                LOG.log(System.Logger.Level.ERROR,
                        "聊天记录保存失败，异常类型：{0}", error.getClass().getName());
                throw new ChatApiException(503, "CHAT_HISTORY_SAVE_FAILED",
                        "保存聊天记录失败，请检查数据库并确认记录后再重试", error);
            }
        }
    }
}
