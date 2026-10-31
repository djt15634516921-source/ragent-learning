package com.dujiteng.ragentlearning.common;

/** 可预期的调用错误：保存 HTTP 状态和供调用者查看的简短信息。 */
public class ChatApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ChatApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ChatApiException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
