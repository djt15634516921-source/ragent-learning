package com.dujiteng.ragentlearning.agent.service.impl;

import com.dujiteng.ragentlearning.agent.entity.AgentConversationDO;
import com.dujiteng.ragentlearning.agent.mapper.AgentConversationMapper;
import com.dujiteng.ragentlearning.agent.mapper.AgentMessageMapper;
import com.dujiteng.ragentlearning.agent.repository.AgentConversationRepository;
import com.dujiteng.ragentlearning.agent.service.AgentConversationService;
import com.dujiteng.ragentlearning.agent.service.handler.AgentRunGate;
import com.dujiteng.ragentlearning.agent.vo.AgentChatResponseVO;
import com.dujiteng.ragentlearning.agent.vo.AgentConversationVO;
import com.dujiteng.ragentlearning.agent.vo.AgentHistoryPageVO;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeRetrievalService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AgentConversationServiceImpl implements AgentConversationService {
    private final AgentConversationMapper conversations;
    private final AgentMessageMapper messages;
    private final AgentConversationRepository repository;
    private final KnowledgeRetrievalService retrieval;
    private final AgentRunGate gate;
    private final JsonMapper json = JsonMapper.builder().build();

    public AgentConversationServiceImpl(AgentConversationMapper conversations, AgentMessageMapper messages,
                                        AgentConversationRepository repository, KnowledgeRetrievalService retrieval,
                                        AgentRunGate gate) {
        this.conversations = conversations;
        this.messages = messages;
        this.repository = repository;
        this.retrieval = retrieval;
        this.gate = gate;
    }

    @Override
    public AgentConversationVO create(long userId, Long knowledgeBaseId, String title) {
        if (knowledgeBaseId == null || knowledgeBaseId <= 0) {
            throw new ChatApiException(400, "KNOWLEDGE_BASE_ID_INVALID", "请选择有效知识库");
        }
        String normalized = title == null || title.isBlank() ? null : title.strip();
        if (normalized != null && normalized.length() > 128) {
            throw new ChatApiException(400, "AGENT_TITLE_INVALID", "会话标题最多 128 字符");
        }
        retrieval.requireOwner(userId, knowledgeBaseId);
        var now = OffsetDateTime.now();
        var row = new AgentConversationDO(UUID.randomUUID().toString(), userId, knowledgeBaseId, normalized, 0, now, now);
        conversations.insert(row);
        return AgentConversationVO.from(row);
    }

    @Override
    public List<AgentConversationVO> list(long userId, Long knowledgeBaseId) {
        return conversations.listOwned(userId, knowledgeBaseId).stream().map(AgentConversationVO::from).toList();
    }

    @Override
    public AgentHistoryPageVO history(long userId, String conversationId, Long beforeId, int size) {
        repository.requireOwned(userId, conversationId);
        if (size < 1 || size > 100 || (beforeId != null && beforeId <= 0)) {
            throw new ChatApiException(400, "AGENT_HISTORY_PAGE_INVALID", "历史分页大小须为 1～100，游标须为正数");
        }
        var rows = messages.page(userId, conversationId, beforeId, size + 1);
        boolean hasMore = rows.size() > size;
        var page = new ArrayList<>(rows.subList(0, Math.min(rows.size(), size)));
        Collections.reverse(page);
        var items = page.stream().map(row -> new AgentHistoryPageVO.Message(
                row.id(), row.role(), row.content(), row.resultStatus(),
                json.readValue(row.toolCalls(), new TypeReference<List<AgentChatResponseVO.ToolCall>>() { }),
                json.readValue(row.sources(), new TypeReference<List<AgentChatResponseVO.Source>>() { }),
                row.createdAt())).toList();
        return new AgentHistoryPageVO(items, hasMore ? page.get(0).id() : null, hasMore);
    }

    @Override
    public void delete(long userId, String conversationId) {
        try (var permit = gate.acquire(userId, conversationId)) {
            repository.requireOwned(userId, conversationId);
            // 外键级联使会话、消息和状态在同一条 DELETE 中清理。
            if (conversations.deleteOwned(userId, conversationId) != 1) {
                throw new ChatApiException(404, "AGENT_CONVERSATION_NOT_FOUND", "Agent 会话不存在");
            }
        }
    }
}
