package com.dujiteng.ragentlearning.agent.repository;

import com.dujiteng.ragentlearning.agent.entity.AgentConversationDO;
import com.dujiteng.ragentlearning.agent.mapper.AgentConversationMapper;
import com.dujiteng.ragentlearning.agent.mapper.AgentMessageMapper;
import com.dujiteng.ragentlearning.agent.mapper.AgentStateMapper;
import com.dujiteng.ragentlearning.agent.state.PgAgentStateStore;
import com.dujiteng.ragentlearning.agent.vo.AgentChatResponseVO;
import com.dujiteng.ragentlearning.common.ChatApiException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AgentConversationRepository {
    private final AgentConversationMapper conversations;
    private final AgentMessageMapper messages;
    private final AgentStateMapper states;
    private final JsonMapper json = JsonMapper.builder().build();

    public AgentConversationRepository(AgentConversationMapper conversations, AgentMessageMapper messages,
                                       AgentStateMapper states) {
        this.conversations = conversations;
        this.messages = messages;
        this.states = states;
    }

    public AgentConversationDO requireOwned(long userId, String id) {
        if (id == null || id.isBlank() || id.length() > 36) {
            throw new ChatApiException(400, "AGENT_CONVERSATION_ID_INVALID", "请选择有效的 Agent 会话");
        }
        var row = conversations.findOwned(userId, id);
        if (row == null) {
            throw new ChatApiException(404, "AGENT_CONVERSATION_NOT_FOUND", "Agent 会话不存在");
        }
        return row;
    }

    @Transactional
    public void appendTurn(AgentConversationDO conversation, String question, AgentChatResponseVO result, String state) {
        long owner = conversation.userId();
        String id = conversation.id();
        String title = question.substring(0, question.offsetByCodePoints(0, Math.min(64, question.codePointCount(0, question.length()))));
        // 更新会话行同时校验版本，避免另一个实例提交的状态被本轮旧结果覆盖。
        if (conversations.advance(owner, id, conversation.revision(), title) != 1) {
            requireOwned(owner, id);
            throw new ChatApiException(409, "AGENT_CONVERSATION_CHANGED", "会话或知识库已变化，请刷新后重试");
        }
        messages.insertTurn(owner, id, question, result.answer(), result.status(),
                json.writeValueAsString(result.toolCalls()), json.writeValueAsString(result.sources()));
        states.upsert(owner, id, PgAgentStateStore.STATE_KEY, state);
    }
}
