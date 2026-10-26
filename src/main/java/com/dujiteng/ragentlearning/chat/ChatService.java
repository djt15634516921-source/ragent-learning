package com.dujiteng.ragentlearning.chat;

import com.dujiteng.ragentlearning.chat.dto.ChatRequest;
import com.dujiteng.ragentlearning.chat.memory.ConversationMemoryStore;
import com.dujiteng.ragentlearning.chat.memory.ConversationMemoryStore.Conversation;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.ModelClient;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** 两种调用共用输入校验和同一份会话历史。 */
@Service
public class ChatService {

    private static final int MAX_QUESTION_CHARS = 8_000;
    private static final int MAX_CONVERSATION_ID_CHARS = 128;
    private static final String SYSTEM_PROMPT =
            "你是一名学习助手，请用中文回答。"
            + "回答第一行固定为【学习助手】。"
            + "随后给出简短解释和一个具体例子。";

    private final ModelClient modelClient;
    private final ConversationMemoryStore memoryStore;

    public ChatService(ModelClient modelClient, ConversationMemoryStore memoryStore) {
        this.modelClient = modelClient;
        this.memoryStore = memoryStore;
    }

    public String chat(String conversationId, String question) {
        ChatRequest input = validateRequest(conversationId, question);
        Conversation conversation = memoryStore.getOrCreate(input.conversationId());
        synchronized (conversation) {
            String answer = modelClient.chat(SYSTEM_PROMPT, conversation.history(), input.question());
            conversation.appendTurn(input.question(), answer);
            return answer;
        }
    }

    public String streamChat(String conversationId, String question, Consumer<String> onDelta,
                             StreamCancellation cancellation) {
        ChatRequest input = validateRequest(conversationId, question);
        cancellation.check();
        Conversation conversation = memoryStore.getOrCreate(input.conversationId());
        synchronized (conversation) {
            cancellation.check();
            String answer = modelClient.stream(
                    SYSTEM_PROMPT, conversation.history(), input.question(), onDelta, cancellation);
            cancellation.check();
            // 只有完整模型回答才保存；生成途中失败不会保存半条回答。
            conversation.appendTurn(input.question(), answer);
            return answer;
        }
    }

    /** 先在 Controller 调用，保证参数错误发生在 SSE 建立之前。 */
    public ChatRequest validateRequest(String conversationId, String question) {
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
        return new ChatRequest(normalizedId, normalized);
    }
}
