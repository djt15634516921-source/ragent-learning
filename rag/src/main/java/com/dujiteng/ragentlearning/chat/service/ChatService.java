package com.dujiteng.ragentlearning.chat.service;

import com.dujiteng.ragentlearning.chat.dto.ChatRequest;
import com.dujiteng.ragentlearning.chat.vo.ChatMessageVO;
import com.dujiteng.ragentlearning.chat.vo.ConversationVO;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import java.util.List;
import java.util.function.Consumer;

public interface ChatService {
    String chat(long userId, String conversationId, String question);
    String streamChat(long userId, String conversationId, String question,
                      Consumer<String> onDelta, StreamCancellation cancellation);
    void prepareConversation(long userId, String conversationId);
    List<ChatMessageVO> history(long userId, String conversationId);
    List<ConversationVO> listConversations(long userId);
    ChatRequest validateRequest(String conversationId, String question);
}
