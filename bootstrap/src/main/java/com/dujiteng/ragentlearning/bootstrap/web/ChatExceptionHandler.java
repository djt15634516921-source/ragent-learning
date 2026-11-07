package com.dujiteng.ragentlearning.bootstrap.web;

import com.dujiteng.ragentlearning.common.ChatApiException;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** 业务、认证及基础设施错误使用一致的 HTTP 状态和错误码。 */
@RestControllerAdvice
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

    @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
    public ResponseEntity<ChatError> handleNotLoggedIn(cn.dev33.satoken.exception.NotLoginException error) {
        return ResponseEntity.status(401).body(new ChatError("AUTH_REQUIRED", "登录已失效，请重新登录"));
    }

    @ExceptionHandler({org.springframework.data.redis.RedisConnectionFailureException.class,
            org.springframework.data.redis.RedisSystemException.class})
    public ResponseEntity<ChatError> handleSessionUnavailable(RuntimeException error) {
        System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR,
                "认证存储不可用，异常类型：{0}", error.getClass().getName());
        return ResponseEntity.status(503).body(new ChatError("AUTH_SESSION_UNAVAILABLE", "登录会话存储暂时不可用，请检查 Redis"));
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
    public ResponseEntity<ChatError> handleDatabase(RuntimeException error) {
        // Lettuce 读超时转换为通用 QueryTimeoutException，按 cause 区分会话存储与业务数据库。
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getName().startsWith("io.lettuce.")) return handleSessionUnavailable(error);
        }
        System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR,
                "数据库访问失败，异常类型：{0}", error.getClass().getName());
        return ResponseEntity.status(503).body(new ChatError("DATABASE_UNAVAILABLE", "数据暂时不可用，请检查数据库连接"));
    }

    @ExceptionHandler({cn.dev33.satoken.exception.NotPermissionException.class,
            cn.dev33.satoken.exception.NotRoleException.class})
    public ResponseEntity<ChatError> handleAccessDenied(RuntimeException error) {
        return ResponseEntity.status(403).body(new ChatError("ACCESS_DENIED", "当前用户没有访问权限"));
    }

    public record ChatError(String code, String message) {
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ChatError> handleOversizedFile(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(413).contentType(MediaType.APPLICATION_JSON)
                .body(new ChatError("FILE_TOO_LARGE", "单个文件不能超过 10 MB"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ChatError> handleMultipart(MultipartException error) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(new ChatError("INVALID_UPLOAD", "文件请求格式不正确，请重新选择文件上传"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ChatError> handleInvalidId(MethodArgumentTypeMismatchException error) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(new ChatError("INVALID_PARAMETER", "请使用有效的数字编号"));
    }
}
