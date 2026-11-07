package com.dujiteng.ragentlearning.rag.core.workflow.impl;

import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeRetrievalService;
import com.dujiteng.ragentlearning.rag.core.rewrite.QueryRewriteService;
import com.dujiteng.ragentlearning.rag.core.workflow.RagContext;
import com.dujiteng.ragentlearning.rag.core.workflow.RagWorkflow;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalOptions;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 固定RAG前处理：改写、澄清判断、检索和无证据判断，不处理HTTP或消息持久化。 */
@Service
public class DefaultRagWorkflow implements RagWorkflow {
    private static final String NO_EVIDENCE = "【Ragent学习助手】\n当前知识库没有检索到足够相关的资料，无法依据资料回答。请补充资料或换一种问法。";
    private static final String CLARIFY = "【Ragent学习助手】\n当前追问的对象不明确，请说明你指的是哪个项目、规则或对象，再继续提问。";
    private final QueryRewriteService rewrite;
    private final KnowledgeRetrievalService retrieval;

    public DefaultRagWorkflow(QueryRewriteService rewrite, KnowledgeRetrievalService retrieval) {
        this.rewrite = rewrite;
        this.retrieval = retrieval;
    }

    @Override
    public void requireOwner(long userId, long knowledgeBaseId) {
        retrieval.requireOwner(userId, knowledgeBaseId);
    }

    @Override
    public RagContext prepare(long userId, long knowledgeBaseId, String question,
                              List<Map<String, String>> history, StreamCancellation cancellation, Boolean rerank) {
        cancellation.check();
        var query = rewrite.rewrite(question, history, cancellation);
        cancellation.check();
        if (query.needsClarification()) return new RagContext(query, List.of(), CLARIFY);
        var result = retrieval.retrieve(userId, knowledgeBaseId, query.searchQuestion(),
                new RetrievalOptions(null, null, null, null, rerank), cancellation);
        cancellation.check();
        return new RagContext(query, result.sources(), result.sources().isEmpty() ? NO_EVIDENCE : null, result);
    }
}
