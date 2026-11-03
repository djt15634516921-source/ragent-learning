package com.dujiteng.ragentlearning.rag.core.workflow;

import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import java.util.List;
import java.util.Map;

public interface RagWorkflow {
    void requireOwner(long userId, long knowledgeBaseId);
    RagContext prepare(long userId, long knowledgeBaseId, String question,
                       List<Map<String, String>> history, StreamCancellation cancellation);
}
