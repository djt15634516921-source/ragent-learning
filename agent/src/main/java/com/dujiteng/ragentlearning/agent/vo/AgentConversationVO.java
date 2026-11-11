package com.dujiteng.ragentlearning.agent.vo;

import com.dujiteng.ragentlearning.agent.entity.AgentConversationDO;
import java.time.OffsetDateTime;

public record AgentConversationVO(String conversationId, long knowledgeBaseId, String title,
                                  OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public static AgentConversationVO from(AgentConversationDO row) {
        return new AgentConversationVO(row.id(), row.knowledgeBaseId(), row.title() == null ? "新会话" : row.title(),
                row.createdAt(), row.updatedAt());
    }
}
