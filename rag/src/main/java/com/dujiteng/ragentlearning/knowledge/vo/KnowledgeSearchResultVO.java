package com.dujiteng.ragentlearning.knowledge.vo;

import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalResult;
import java.util.List;

public record KnowledgeSearchResultVO(List<RetrievedSourceVO> sources, RetrievalTraceVO retrieval) {
    public static KnowledgeSearchResultVO from(RetrievalResult result) {
        return new KnowledgeSearchResultVO(result.sources(), RetrievalTraceVO.from(result));
    }
}
