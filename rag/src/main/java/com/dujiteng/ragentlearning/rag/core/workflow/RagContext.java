package com.dujiteng.ragentlearning.rag.core.workflow;

import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import com.dujiteng.ragentlearning.rag.core.rewrite.RewriteResult;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalResult;
import java.util.List;

/** 本轮预处理结果；directAnswer非空表示澄清或无证据，无需调用回答模型。 */
public record RagContext(RewriteResult rewrite, List<RetrievedSourceVO> sources, String directAnswer, RetrievalResult retrieval) {
    public RagContext {
        sources = List.copyOf(sources);
    }
    public RagContext(RewriteResult rewrite, List<RetrievedSourceVO> sources, String directAnswer) { this(rewrite, sources, directAnswer, null); }
}
