package com.dujiteng.ragentlearning.infra.rerank;

import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import java.util.List;

/** 外部模型只接收问题和文本，不依赖rag模块的数据库或HTTP响应类型。 */
public interface RerankService {
    RerankResponse rerank(String query, List<String> documents, StreamCancellation cancellation);
    String model();

    record Score(int index, double relevance) { }
    record RerankResponse(List<Score> scores, Long totalTokens) {
        public RerankResponse { scores = List.copyOf(scores); }
    }
}
