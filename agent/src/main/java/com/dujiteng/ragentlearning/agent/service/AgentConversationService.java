package com.dujiteng.ragentlearning.agent.service;

import com.dujiteng.ragentlearning.agent.vo.AgentConversationVO;
import com.dujiteng.ragentlearning.agent.vo.AgentHistoryPageVO;
import java.util.List;

public interface AgentConversationService {
    AgentConversationVO create(long userId, Long knowledgeBaseId, String title);

    List<AgentConversationVO> list(long userId, Long knowledgeBaseId);

    AgentHistoryPageVO history(long userId, String conversationId, Long beforeId, int size);

    void delete(long userId, String conversationId);
}
