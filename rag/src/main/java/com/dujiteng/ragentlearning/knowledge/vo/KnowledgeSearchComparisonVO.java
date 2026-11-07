package com.dujiteng.ragentlearning.knowledge.vo;

import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalComparison;

public record KnowledgeSearchComparisonVO(boolean sameCandidates, KnowledgeSearchResultVO baseline, KnowledgeSearchResultVO reranked) {
    public static KnowledgeSearchComparisonVO from(RetrievalComparison comparison) {
        return new KnowledgeSearchComparisonVO(true, KnowledgeSearchResultVO.from(comparison.baseline()), KnowledgeSearchResultVO.from(comparison.reranked()));
    }
}
