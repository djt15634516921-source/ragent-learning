package com.dujiteng.ragentlearning.chat;

import com.dujiteng.ragentlearning.chat.memory.ConversationMemoryStore;
import com.dujiteng.ragentlearning.chat.memory.ConversationMemoryStore.Conversation;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.ModelClient;
import org.springframework.stereotype.Service;

/** 业务层：校验输入，读取历史，调用模型，成功后保存这一轮。 */
@Service
public class ChatService {

    private static final int MAX_QUESTION_CHARS = 8_000;
    private static final int MAX_CONVERSATION_ID_CHARS = 128;
    private static final String SYSTEM_PROMPT =
            "你是一名Java后端学习助手，请用中文回答。"
            + "回答第一行固定为【Ragent学习助手】。"
            + "随后给出简短解释和一个具体例子。";

    private final ModelClient modelClient;
    private final ConversationMemoryStore memoryStore;

    // Spring 根据构造方法，把模型客户端和会话存储对象注入进来。
    public ChatService(ModelClient modelClient, ConversationMemoryStore memoryStore) {
        this.modelClient = modelClient;
        this.memoryStore = memoryStore;
    }

    public String chat(String conversationId, String question) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ChatApiException(400, "CONVERSATION_ID_REQUIRED", "conversationId 不能为空");
        }
        String normalizedId = conversationId.strip();
        if (normalizedId.length() > MAX_CONVERSATION_ID_CHARS) {
            throw new ChatApiException(400, "CONVERSATION_ID_TOO_LONG", "conversationId 最多 128 个字符");
        }
        if (question == null || question.isBlank()) {
            throw new ChatApiException(400, "QUESTION_REQUIRED", "question 不能为空");
        }
        String normalized = question.strip();
        if (normalized.length() > MAX_QUESTION_CHARS) {
            throw new ChatApiException(400, "QUESTION_TOO_LONG", "本课接口的问题长度上限为 8000 个字符");
        }

        Conversation conversation = memoryStore.getOrCreate(normalizedId);

        // 同一会话的“读历史 -> 调模型 -> 保存”按顺序执行；不同会话用不同的锁。
        synchronized (conversation) {
            String answer = modelClient.chat(SYSTEM_PROMPT, conversation.history(), normalized);

            // 如果上面抛出异常，这里不会执行，历史就保持原样。
            conversation.appendTurn(normalized, answer);
            return answer;
        }
    }
}
