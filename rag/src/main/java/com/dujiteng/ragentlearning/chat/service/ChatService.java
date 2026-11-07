package com.dujiteng.ragentlearning.chat.service;

import com.dujiteng.ragentlearning.chat.dto.ChatRequest;
import com.dujiteng.ragentlearning.chat.vo.ChatMessageVO;
import com.dujiteng.ragentlearning.chat.vo.ChatResponseVO;
import com.dujiteng.ragentlearning.chat.vo.ConversationVO;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import java.util.List;
import java.util.function.Consumer;

public interface ChatService {
    default ChatResponseVO chat(long userId, String conversationId, String question, Long knowledgeBaseId) {
        return chat(userId, conversationId, question, knowledgeBaseId, null);
    }
    ChatResponseVO chat(long userId, String conversationId, String question, Long knowledgeBaseId, Boolean rerank);
    default ChatResponseVO streamChat(long userId, String conversationId, String question, Long knowledgeBaseId,
                      Consumer<String> onDelta, StreamCancellation cancellation) {
        return streamChat(userId, conversationId, question, knowledgeBaseId, null, onDelta, cancellation);
    }
    ChatResponseVO streamChat(long userId, String conversationId, String question, Long knowledgeBaseId, Boolean rerank,
                      Consumer<String> onDelta, StreamCancellation cancellation);
    void prepareConversation(long userId, String conversationId, Long knowledgeBaseId);
    List<ChatMessageVO> history(long userId, String conversationId);
    List<ConversationVO> listConversations(long userId);
    ChatRequest validateRequest(String conversationId, String question);
}
