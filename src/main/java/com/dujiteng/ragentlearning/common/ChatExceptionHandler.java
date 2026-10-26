package com.dujiteng.ragentlearning.common;

import com.dujiteng.ragentlearning.chat.ChatController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只处理新增聊天入口的预期错误，不改变 hello 接口。 */
@RestControllerAdvice(assignableTypes = ChatController.class)
public class ChatExceptionHandler {

    @ExceptionHandler(ChatApiException.class)
    public ResponseEntity<ChatError> handleChatError(ChatApiException error) {
        return ResponseEntity.status(error.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatError(error.code(), error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ChatError> handleBadJson(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(new ChatError(
                "INVALID_JSON", "请发送 JSON 请求体，例如 {\"conversationId\":\"study-001\",\"question\":\"你好\"}"));
    }

    public record ChatError(String code, String message) {
    }
}
