package com.dujiteng.ragentlearning.chat.dto;

/** knowledgeBaseId 为空时普通聊天；同一会话固定知识库，切换时新建会话。 */
public record ChatRequest(String conversationId, String question, Long knowledgeBaseId) {
    public ChatRequest(String conversationId, String question) { this(conversationId, question, null); }
}
