package com.dujiteng.ragentlearning.knowledge.service;

import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import com.dujiteng.ragentlearning.knowledge.vo.KnowledgeSearchSettingsVO;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalOptions;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalResult;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalComparison;
import java.util.List;

public interface KnowledgeRetrievalService {
    void requireOwner(long userId, long knowledgeBaseId);
    List<RetrievedSourceVO> search(long userId, long knowledgeBaseId, String question);
    List<RetrievedSourceVO> search(long userId, long knowledgeBaseId, String question, Integer topK, Double minScore);
    KnowledgeSearchSettingsVO settings(long userId, long knowledgeBaseId);
    RetrievalResult retrieve(long userId, long knowledgeBaseId, String question, RetrievalOptions options, StreamCancellation cancellation);
    RetrievalComparison compare(long userId, long knowledgeBaseId, String question, RetrievalOptions options, StreamCancellation cancellation);
}
