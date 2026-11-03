package com.dujiteng.ragentlearning.rag.core.rewrite;

import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import java.util.List;
import java.util.Map;

public interface QueryRewriteService {
    RewriteResult rewrite(String question, List<Map<String, String>> history, StreamCancellation cancellation);
}
