package com.dujiteng.ragentlearning.rag.core.workflow;

import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import com.dujiteng.ragentlearning.rag.core.rewrite.RewriteResult;
import java.util.List;

/** 本轮预处理结果；directAnswer非空表示澄清或无证据，无需调用回答模型。 */
public record RagContext(RewriteResult rewrite, List<RetrievedSourceVO> sources, String directAnswer) {
    public RagContext {
        sources = List.copyOf(sources);
    }
}
