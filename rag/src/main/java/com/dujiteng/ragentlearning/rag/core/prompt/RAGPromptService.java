package com.dujiteng.ragentlearning.rag.core.prompt;

import com.dujiteng.ragentlearning.infra.llm.LLMRequest;
import com.dujiteng.ragentlearning.rag.core.workflow.RagContext;
import java.util.List;
import java.util.Map;

public interface RAGPromptService {
    LLMRequest build(String basePrompt, String question, List<Map<String, String>> history, RagContext context);
}
