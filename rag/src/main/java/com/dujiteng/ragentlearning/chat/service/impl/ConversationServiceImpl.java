package com.dujiteng.ragentlearning.chat.service.impl;

import com.dujiteng.ragentlearning.chat.memory.ChatMemoryRepository;
import com.dujiteng.ragentlearning.chat.service.ConversationService;
import com.dujiteng.ragentlearning.chat.stream.ChatStreamRegistry;
import com.dujiteng.ragentlearning.chat.stream.ChatStreamRegistry.CancelResult;
import com.dujiteng.ragentlearning.chat.vo.ChatHistoryPageVO;
import org.springframework.stereotype.Service;

@Service
public class ConversationServiceImpl implements ConversationService {
    private final ChatMemoryRepository memory;
    private final ChatStreamRegistry streams;

    public ConversationServiceImpl(ChatMemoryRepository memory, ChatStreamRegistry streams) {
        this.memory = memory; this.streams = streams;
    }

    @Override
    public void delete(long userId, String conversationId) {
        memory.delete(userId, conversationId);
        streams.cancelConversation(userId, conversationId);
    }

    @Override
    public ChatHistoryPageVO historyPage(long userId, String conversationId, Long beforeId, int size) {
        return memory.page(userId, conversationId, beforeId, size);
    }

    @Override
    public CancelResult cancelStream(long userId, String conversationId, String streamId) {
        memory.requireOwner(userId, conversationId);
        return streams.cancel(userId, conversationId, streamId);
    }
}
