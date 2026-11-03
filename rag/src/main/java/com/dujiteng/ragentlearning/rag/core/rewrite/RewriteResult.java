package com.dujiteng.ragentlearning.rag.core.rewrite;

/** RAG内部改写结果；HTTP响应由外层转换，不反向依赖聊天VO。 */
public record RewriteResult(String originalQuestion, String searchQuestion, String status, long durationMs) {
    public boolean needsClarification() { return "AMBIGUOUS".equals(status); }
}
