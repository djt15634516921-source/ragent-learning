package com.dujiteng.ragentlearning.knowledge.service.impl;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.embedding.EmbeddingClient;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeVectorMapper;
import com.dujiteng.ragentlearning.knowledge.repository.KnowledgeRepository;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeRetrievalService;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRetrievalServiceImpl implements KnowledgeRetrievalService {
    private final KnowledgeRepository knowledge;
    private final KnowledgeVectorMapper vectors;
    private final EmbeddingClient embedding;
    private final int topK;
    private final double minimum;
    public KnowledgeRetrievalServiceImpl(KnowledgeRepository knowledge, KnowledgeVectorMapper vectors, EmbeddingClient embedding,
            @Value("${rag.top-k:4}") int topK, @Value("${rag.min-score:0.35}") double minimum) {
        if (topK < 1 || topK > 10 || !Double.isFinite(minimum) || minimum < -1 || minimum > 1) {
            throw new IllegalArgumentException("检索配置不正确");
        }
        this.knowledge = knowledge;
        this.vectors = vectors;
        this.embedding = embedding;
        this.topK = topK;
        this.minimum = minimum;
    }
    @Override
    public void requireOwner(long userId, long knowledgeBaseId) {
        try { knowledge.requireOwner(userId, knowledgeBaseId);
        } catch (DataAccessException error) { throw database(error); }
    }
    @Override
    public List<RetrievedSourceVO> search(long userId, long knowledgeBaseId, String question) {
        if (question == null || question.isBlank() || question.length() > 8000) {
            throw new ChatApiException(400, "QUESTION_INVALID", "检索问题不能为空且最多 8000 字符");
        }
        requireOwner(userId, knowledgeBaseId);
        try {
            if (vectors.countReady(userId, knowledgeBaseId, embedding.profile()) == 0) {
                throw new ChatApiException(409, "KNOWLEDGE_NOT_INDEXED", "知识库暂无当前模型可用的向量，请先切分并向量化文档");
            }
            String query = embedding.embed(List.of(question.strip())).get(0);
            var matches = vectors.search(userId, knowledgeBaseId, embedding.profile(), query, minimum, topK);
            List<RetrievedSourceVO> result = new ArrayList<>();
            int characters = 0;
            for (var source : matches) {
                int size = source.content().codePointCount(0, source.content().length());
                if (characters + size <= 6000) { result.add(source); characters += size; }
            }
            return List.copyOf(result);
        } catch (DataAccessException error) { throw database(error); }
    }
    private static ChatApiException database(DataAccessException error) {
        return new ChatApiException(503, "KNOWLEDGE_SEARCH_DATABASE_FAILED", "知识库检索数据库暂不可用", error);
    }
}
