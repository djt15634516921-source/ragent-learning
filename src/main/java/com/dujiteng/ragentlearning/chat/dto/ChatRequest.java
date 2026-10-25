package com.dujiteng.ragentlearning.chat.dto;

/** 同一段聊天使用相同的 conversationId；每次只提交新的 question。 */
public record ChatRequest(String conversationId, String question) {
}
