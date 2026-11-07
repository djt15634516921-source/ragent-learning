package com.dujiteng.ragentlearning.chat.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dujiteng.ragentlearning.chat.service.ConversationService;
import com.dujiteng.ragentlearning.chat.stream.ChatStreamRegistry.CancelResult;
import com.dujiteng.ragentlearning.chat.vo.ChatHistoryPageVO;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations/{conversationId}")
public class ConversationController {
    private final ConversationService service;

    public ConversationController(ConversationService service) { this.service = service; }

    @DeleteMapping
    public Map<String, Boolean> delete(@PathVariable String conversationId) {
        service.delete(StpUtil.getLoginIdAsLong(), conversationId);
        return Map.of("deleted", true);
    }

    @GetMapping("/messages/page")
    public ChatHistoryPageVO page(@PathVariable String conversationId, @RequestParam(required = false) Long beforeId,
                                 @RequestParam(defaultValue = "20") int size) {
        return service.historyPage(StpUtil.getLoginIdAsLong(), conversationId, beforeId, size);
    }

    @PostMapping("/streams/{streamId}/cancel")
    public CancelResult cancel(@PathVariable String conversationId, @PathVariable String streamId) {
        return service.cancelStream(StpUtil.getLoginIdAsLong(), conversationId, streamId);
    }
}
