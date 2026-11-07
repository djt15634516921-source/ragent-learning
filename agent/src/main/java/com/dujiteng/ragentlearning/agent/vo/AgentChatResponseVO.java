package com.dujiteng.ragentlearning.agent.vo;

import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;

public record AgentChatResponseVO(String status, String answer, List<ToolCall> toolCalls, List<Source> sources) {
    public record ToolCall(int sequence, String name, String query, String status, String errorCode,
                           List<Integer> citationIds) {
    }

    public record Source(int citationId, RetrievedSourceVO fragment) {
    }
}
