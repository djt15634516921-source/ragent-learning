package com.dujiteng.ragentlearning.rag.core.retrieval;

public record RetrievalOptions(Integer topK, Double minScore, Integer recallBudget, Integer candidateLimit, Boolean rerank) {
    public static RetrievalOptions defaults() { return new RetrievalOptions(null, null, null, null, null); }
}
