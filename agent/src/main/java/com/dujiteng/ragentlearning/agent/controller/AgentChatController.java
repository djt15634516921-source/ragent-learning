package com.dujiteng.ragentlearning.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dujiteng.ragentlearning.agent.dto.AgentChatRequest;
import com.dujiteng.ragentlearning.agent.service.AgentChatService;
import com.dujiteng.ragentlearning.agent.vo.AgentChatResponseVO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/agent")
public class AgentChatController {
    private final AgentChatService agent;

    public AgentChatController(AgentChatService agent) {
        this.agent = agent;
    }

    @PostMapping("/chat")
    public AgentChatResponseVO chat(@PathVariable long knowledgeBaseId, @RequestBody AgentChatRequest request) {
        return agent.chat(StpUtil.getLoginIdAsLong(), knowledgeBaseId,
                request == null ? null : request.conversationId(), request == null ? null : request.question());
    }
}
