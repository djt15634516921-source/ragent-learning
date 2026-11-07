package com.dujiteng.ragentlearning.knowledge.dto;

import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalOptions;

public record KnowledgeSearchRequest(String question, Integer topK, Double minScore,
                                     Integer recallBudget, Integer candidateLimit, Boolean rerank) {
    public RetrievalOptions options() { return new RetrievalOptions(topK, minScore, recallBudget, candidateLimit, rerank); }
}
