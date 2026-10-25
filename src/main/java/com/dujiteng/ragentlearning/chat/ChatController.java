package com.dujiteng.ragentlearning.chat;

import com.dujiteng.ragentlearning.chat.dto.ChatRequest;
import com.dujiteng.ragentlearning.chat.dto.ChatResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP 入口：取出会话编号和问题，交给 Service，包装回答。 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat", produces = "application/json;charset=UTF-8")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String conversationId = request == null ? null : request.conversationId();
        String question = request == null ? null : request.question();
        String answer = chatService.chat(conversationId, question);
        return new ChatResponse(answer);
    }
}
