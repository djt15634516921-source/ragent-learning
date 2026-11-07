package com.dujiteng.ragentlearning.agent.service;

import com.dujiteng.ragentlearning.agent.vo.AgentChatResponseVO;

public interface AgentChatService {
    AgentChatResponseVO chat(long userId, long knowledgeBaseId, String question);
}
