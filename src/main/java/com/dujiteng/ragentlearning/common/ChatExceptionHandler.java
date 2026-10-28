package com.dujiteng.ragentlearning.common;

import com.dujiteng.ragentlearning.chat.ChatController;
import com.dujiteng.ragentlearning.system.auth.AuthController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 处理聊天和注册入口的预期错误；登录过滤器的错误由 SecurityConfig 返回。 */
@RestControllerAdvice(assignableTypes = {ChatController.class, AuthController.class})
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
                "INVALID_JSON", "请检查 JSON 请求体格式，并确认字段名称正确"));
    }

    public record ChatError(String code, String message) {
    }
}
