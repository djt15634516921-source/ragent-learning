package com.dujiteng.ragentlearning.chat;

import com.dujiteng.ragentlearning.chat.dto.ChatRequest;
import com.dujiteng.ragentlearning.chat.memory.ConversationMemoryStore;
import com.dujiteng.ragentlearning.chat.memory.ConversationMemoryStore.Conversation;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.ModelClient;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private static final int MAX_QUESTION_CHARS = 8_000;
    private static final int MAX_CONVERSATION_ID_CHARS = 128;
    // 如果你已经自定义这个提示词，合并时保留你自己的版本。
    private static final String SYSTEM_PROMPT =
            "你是一名Java后端学习助手，请用中文回答。"
            + "回答第一行固定为【Ragent学习助手】。"
            + "随后给出简短解释和一个具体例子。";
    private final ModelClient modelClient;
    private final ConversationMemoryStore memoryStore;

    public ChatService(ModelClient modelClient, ConversationMemoryStore memoryStore) {
        this.modelClient = modelClient;
        this.memoryStore = memoryStore;
    }

    public String chat(long userId, String conversationId, String question) {
        ChatRequest input = validateRequest(conversationId, question);
        Conversation conversation = memoryStore.getOrCreate(userId, input.conversationId());
        synchronized (conversation) {
            String answer = modelClient.chat(SYSTEM_PROMPT, conversation.history(), input.question());
            conversation.appendTurn(input.question(), answer);
            return answer;
        }
    }

    public String streamChat(long userId, String conversationId, String question, Consumer<String> onDelta,
                             StreamCancellation cancellation) {
        ChatRequest input = validateRequest(conversationId, question);
        cancellation.check();
        Conversation conversation = memoryStore.getOrCreate(userId, input.conversationId());
        synchronized (conversation) {
            cancellation.check();
            String answer = modelClient.stream(
                    SYSTEM_PROMPT, conversation.history(), input.question(), onDelta, cancellation);
            cancellation.check();
            conversation.appendTurn(input.question(), answer);
            return answer;
        }
    }

    /** 在 SSE 建立之前确定归属；worker 随后仍会检查。新会话此时可能只有会话行、还没有消息。 */
    public void prepareConversation(long userId, String conversationId) {
        memoryStore.getOrCreate(userId, conversationId);
    }

    public List<Map<String, String>> history(long userId, String conversationId) {
        return memoryStore.history(userId, validateConversationId(conversationId));
    }

    public List<Map<String, String>> listConversations(long userId) {
        return memoryStore.listConversations(userId);
    }

    public ChatRequest validateRequest(String conversationId, String question) {
        String normalizedId = validateConversationId(conversationId);
        if (question == null || question.isBlank()) {
            throw new ChatApiException(400, "QUESTION_REQUIRED", "question 不能为空");
        }
        String normalized = question.strip();
        if (normalized.length() > MAX_QUESTION_CHARS) {
            throw new ChatApiException(400, "QUESTION_TOO_LONG", "本课接口的问题长度上限为 8000 个字符");
        }
        return new ChatRequest(normalizedId, normalized);
    }

    private String validateConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ChatApiException(400, "CONVERSATION_ID_REQUIRED", "conversationId 不能为空");
        }
        String normalized = conversationId.strip();
        if (normalized.length() > MAX_CONVERSATION_ID_CHARS) {
            throw new ChatApiException(400, "CONVERSATION_ID_TOO_LONG", "conversationId 最多 128 个字符");
        }
        return normalized;
    }
}
