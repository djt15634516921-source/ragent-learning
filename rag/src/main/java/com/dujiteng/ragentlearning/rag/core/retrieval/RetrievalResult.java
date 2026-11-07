package com.dujiteng.ragentlearning.rag.core.retrieval;

import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;

public record RetrievalResult(List<RetrievedSourceVO> sources, RetrievalTrace trace) {
    public RetrievalResult { sources = List.copyOf(sources); }
}
