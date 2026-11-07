package com.dujiteng.ragentlearning.knowledge.service.impl;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.embedding.EmbeddingClient;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import com.dujiteng.ragentlearning.knowledge.config.KnowledgeProcessingSettings;
import com.dujiteng.ragentlearning.knowledge.mapper.KnowledgeVectorMapper;
import com.dujiteng.ragentlearning.knowledge.repository.KnowledgeRepository;
import com.dujiteng.ragentlearning.knowledge.service.KnowledgeRetrievalService;
import com.dujiteng.ragentlearning.knowledge.vo.KnowledgeSearchSettingsVO;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalBudget;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalComparison;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalOptions;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalResult;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalTrace;
import com.dujiteng.ragentlearning.rag.core.retrieval.postprocessor.RerankPostProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRetrievalServiceImpl implements KnowledgeRetrievalService {
    private final KnowledgeRepository knowledge;
    private final KnowledgeVectorMapper vectors;
    private final EmbeddingClient embedding;
    private final KnowledgeProcessingSettings processing;
    private final RerankPostProcessor rerank;
    private final RetrievalBudget defaults;
    private final double minimum;
    private final int maxContextCharacters;
    private final boolean rerankEnabled;

    public KnowledgeRetrievalServiceImpl(KnowledgeRepository knowledge, KnowledgeVectorMapper vectors, EmbeddingClient embedding,
            KnowledgeProcessingSettings processing, RerankPostProcessor rerank,
            @Value("${rag.top-k:4}") int topK, @Value("${rag.min-score:0.35}") double minimum,
            @Value("${rag.max-context-chars:6000}") int maxContextCharacters,
            @Value("${rag.recall-budget:16}") int recallBudget, @Value("${rag.rerank-candidate-limit:12}") int candidateLimit,
            @Value("${rag.rerank.enabled:false}") boolean rerankEnabled) {
        this.defaults = new RetrievalBudget(recallBudget, candidateLimit, topK);
        if (!Double.isFinite(minimum) || minimum < -1 || minimum > 1 || maxContextCharacters < 200 || maxContextCharacters > 20000) {
            throw new IllegalArgumentException("检索分数或上下文字符预算不正确");
        }
        this.knowledge = knowledge;
        this.vectors = vectors;
        this.embedding = embedding;
        this.processing = processing;
        this.rerank = rerank;
        this.minimum = minimum;
        this.maxContextCharacters = maxContextCharacters;
        this.rerankEnabled = rerankEnabled;
    }

    @Override
    public void requireOwner(long userId, long knowledgeBaseId) {
        try { knowledge.requireOwner(userId, knowledgeBaseId); }
        catch (DataAccessException error) { throw database(error); }
    }

    @Override
    public KnowledgeSearchSettingsVO settings(long userId, long knowledgeBaseId) {
        requireOwner(userId, knowledgeBaseId);
        return new KnowledgeSearchSettingsVO(defaults.contextTopK(), minimum, 20, maxContextCharacters, processing.chunkSize(), processing.overlap(),
                embedding.model(), EmbeddingClient.DIMENSIONS, embedding.profile(), defaults.recallBudget(), defaults.candidateLimit(),
                rerankEnabled, rerank.model(), rerank.maxInputCharacters());
    }

    @Override
    public List<RetrievedSourceVO> search(long userId, long knowledgeBaseId, String question) {
        return retrieve(userId, knowledgeBaseId, question, RetrievalOptions.defaults(), new StreamCancellation()).sources();
    }

    @Override
    public List<RetrievedSourceVO> search(long userId, long knowledgeBaseId, String question, Integer topK, Double minScore) {
        return retrieve(userId, knowledgeBaseId, question, new RetrievalOptions(topK, minScore, null, null, null), new StreamCancellation()).sources();
    }

    @Override
    public RetrievalResult retrieve(long userId, long knowledgeBaseId, String question, RetrievalOptions options, StreamCancellation cancellation) {
        var request = options == null ? RetrievalOptions.defaults() : options;
        var snapshot = recall(userId, knowledgeBaseId, question, request, cancellation);
        return finish(snapshot, request.rerank() == null ? rerankEnabled : request.rerank(), cancellation);
    }

    @Override
    public RetrievalComparison compare(long userId, long knowledgeBaseId, String question, RetrievalOptions options, StreamCancellation cancellation) {
        var snapshot = recall(userId, knowledgeBaseId, question, options == null ? RetrievalOptions.defaults() : options, cancellation);
        // 两种方案共享一次问题向量和同一候选快照，避免把数据变更或两次召回差异误认为重排效果。
        var baseline = finish(snapshot, false, cancellation);
        var reranked = finish(snapshot, true, cancellation);
        return new RetrievalComparison(baseline, reranked);
    }

    private Snapshot recall(long userId, long baseId, String question, RetrievalOptions options, StreamCancellation cancellation) {
        cancellation.check();
        if (question == null || question.isBlank() || question.length() > 8000) {
            throw new ChatApiException(400, "QUESTION_INVALID", "检索问题不能为空且最多8000字符");
        }
        requireOwner(userId, baseId);
        var budget = budget(options);
        double threshold = options.minScore() == null ? minimum : options.minScore();
        if (!Double.isFinite(threshold) || threshold < -1 || threshold > 1) {
            throw new ChatApiException(400, "SEARCH_PARAMETERS_INVALID", "最低相似度须为-1～1的有限数值");
        }
        long started = System.nanoTime();
        try {
            String profile = embedding.profile();
            if (vectors.countReady(userId, baseId, profile) == 0) {
                throw new ChatApiException(409, "KNOWLEDGE_NOT_INDEXED", "知识库暂无当前模型可用的向量，请先切分并向量化文档");
            }
            long embeddingStarted = System.nanoTime();
            String query = embedding.embed(List.of(question.strip())).get(0);
            cancellation.check();
            long embeddingMs = elapsed(embeddingStarted), recallStarted = System.nanoTime();
            var recalled = List.copyOf(vectors.search(userId, baseId, profile, query, threshold, budget.recallBudget()));
            long recallMs = elapsed(recallStarted);
            cancellation.check();
            var candidates = List.copyOf(recalled.subList(0, Math.min(budget.candidateLimit(), recalled.size())));
            return new Snapshot(question.strip(), budget, started, embeddingMs, recallMs, recalled, candidates);
        } catch (DataAccessException error) { throw database(error); }
    }

    private RetrievalResult finish(Snapshot snapshot, boolean enabled, StreamCancellation cancellation) {
        var ranked = rerank.process(snapshot.question(), snapshot.candidates(), enabled, cancellation);
        List<RetrievedSourceVO> selected = new ArrayList<>();
        int characters = 0;
        for (var source : ranked.ordered()) {
            int size = source.content().codePointCount(0, source.content().length());
            if (characters + size <= maxContextCharacters) { selected.add(source); characters += size; }
            if (selected.size() == snapshot.budget().contextTopK()) break;
        }
        cancellation.check();
        var trace = new RetrievalTrace(snapshot.budget(), ranked.status(), ranked.model(), ranked.errorCode(),
                snapshot.embeddingMs(), snapshot.recallMs(), ranked.durationMs(), elapsed(snapshot.started()), characters,
                ranked.inputCharacters(), ranked.attempts(), ranked.totalTokens(), RetrievalTrace.Candidate.from(snapshot.recalled()),
                RetrievalTrace.Candidate.from(snapshot.candidates()), RetrievalTrace.Candidate.from(ranked.ordered()));
        return new RetrievalResult(selected, trace);
    }

    private RetrievalBudget budget(RetrievalOptions request) {
        int topK = request.topK() == null ? defaults.contextTopK() : request.topK();
        int recall = request.recallBudget() == null ? Math.max(defaults.recallBudget(), topK) : request.recallBudget();
        int candidates = request.candidateLimit() == null ? Math.max(defaults.candidateLimit(), topK) : request.candidateLimit();
        try { return new RetrievalBudget(recall, candidates, topK); }
        catch (IllegalArgumentException error) { throw new ChatApiException(400, "SEARCH_PARAMETERS_INVALID", error.getMessage()); }
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static ChatApiException database(DataAccessException error) {
        return new ChatApiException(503, "KNOWLEDGE_SEARCH_DATABASE_FAILED", "知识库检索数据库暂不可用", error);
    }
    private record Snapshot(String question, RetrievalBudget budget, long started, long embeddingMs, long recallMs,
                            List<RetrievedSourceVO> recalled, List<RetrievedSourceVO> candidates) { }
}
