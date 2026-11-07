package com.dujiteng.ragentlearning.knowledge.vo;

import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalResult;
import com.dujiteng.ragentlearning.rag.core.retrieval.RetrievalTrace;
import java.util.List;
import java.util.stream.IntStream;

public record RetrievalTraceVO(int recallBudget, int candidateLimit, int contextTopK, int recalledCount,
        int candidateCount, int returnedCount, int contextCharacters, int rerankInputCharacters,
        String rerankStatus, String rerankModel, String errorCode, long embeddingMs, long recallMs,
        long rerankMs, long totalMs, int rerankAttempts, Long rerankTokens,
        List<CandidateVO> recalledCandidates, List<CandidateVO> beforeRerank, List<CandidateVO> afterRerank) {
    public static RetrievalTraceVO from(RetrievalResult result) {
        if (result == null || result.trace() == null) return null;
        var trace = result.trace();
        return new RetrievalTraceVO(trace.budget().recallBudget(), trace.budget().candidateLimit(), trace.budget().contextTopK(),
                trace.recalled().size(), trace.before().size(), result.sources().size(), trace.contextCharacters(), trace.rerankInputCharacters(),
                trace.status(), trace.model(), trace.errorCode(), trace.embeddingMs(), trace.recallMs(), trace.rerankMs(), trace.totalMs(),
                trace.rerankAttempts(), trace.rerankTokens(), candidates(trace.recalled()), candidates(trace.before()), candidates(trace.after()));
    }

    private static List<CandidateVO> candidates(List<RetrievalTrace.Candidate> rows) {
        return IntStream.range(0, rows.size()).mapToObj(index -> {
            var row = rows.get(index);
            return new CandidateVO(index + 1, row.chunkId(), row.documentId(), row.filename(), row.chunkIndex(), row.vectorScore(), row.rerankScore());
        }).toList();
    }

    public record CandidateVO(int rank, long chunkId, long documentId, String filename, int chunkIndex,
                              double vectorScore, Double rerankScore) { }
}
