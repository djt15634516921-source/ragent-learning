package com.dujiteng.ragentlearning.chat.service;

import com.dujiteng.ragentlearning.chat.stream.ChatStreamRegistry.CancelResult;
import com.dujiteng.ragentlearning.chat.vo.ChatHistoryPageVO;

public interface ConversationService {
    void delete(long userId, String conversationId);
    ChatHistoryPageVO historyPage(long userId, String conversationId, Long beforeId, int size);
    CancelResult cancelStream(long userId, String conversationId, String streamId);
}
