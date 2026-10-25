package com.dujiteng.ragentlearning.chat;

import com.dujiteng.ragentlearning.chat.dto.ChatRequest;
import com.dujiteng.ragentlearning.chat.dto.ChatResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP 入口：接收 JSON 问题，交给业务层，返回 JSON 回答。 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat", produces = "application/json;charset=UTF-8")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String question = request == null ? null : request.question();
        return new ChatResponse(chatService.chat(question));
    }
}
