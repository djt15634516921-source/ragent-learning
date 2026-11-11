package com.dujiteng.ragentlearning.agent.vo;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentHistoryPageVO(List<Message> messages, Long nextBeforeId, boolean hasMore) {
    public record Message(long id, String role, String content, String status,
                          List<AgentChatResponseVO.ToolCall> toolCalls, List<AgentChatResponseVO.Source> sources,
                          OffsetDateTime createdAt) {
    }
}
