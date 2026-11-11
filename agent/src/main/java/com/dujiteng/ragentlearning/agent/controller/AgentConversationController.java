package com.dujiteng.ragentlearning.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dujiteng.ragentlearning.agent.dto.AgentConversationCreateRequest;
import com.dujiteng.ragentlearning.agent.service.AgentConversationService;
import com.dujiteng.ragentlearning.agent.vo.AgentConversationVO;
import com.dujiteng.ragentlearning.agent.vo.AgentHistoryPageVO;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/conversations")
public class AgentConversationController {
    private final AgentConversationService conversations;

    public AgentConversationController(AgentConversationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentConversationVO create(@RequestBody AgentConversationCreateRequest request) {
        return conversations.create(StpUtil.getLoginIdAsLong(), request == null ? null : request.knowledgeBaseId(),
                request == null ? null : request.title());
    }

    @GetMapping
    public List<AgentConversationVO> list(@RequestParam(required = false) Long knowledgeBaseId) {
        return conversations.list(StpUtil.getLoginIdAsLong(), knowledgeBaseId);
    }

    @GetMapping("/{conversationId}/messages")
    public AgentHistoryPageVO history(@PathVariable String conversationId,
                                      @RequestParam(required = false) Long beforeId,
                                      @RequestParam(defaultValue = "20") int size) {
        return conversations.history(StpUtil.getLoginIdAsLong(), conversationId, beforeId, size);
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String conversationId) {
        conversations.delete(StpUtil.getLoginIdAsLong(), conversationId);
    }
}
