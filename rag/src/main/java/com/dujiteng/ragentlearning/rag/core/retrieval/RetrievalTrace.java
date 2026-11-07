package com.dujiteng.ragentlearning.rag.core.retrieval;

import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;

/** 本次检索的运行信息，不含密钥；数组顺序就是该阶段的名次。 */
public record RetrievalTrace(RetrievalBudget budget, String status, String model, String errorCode,
        long embeddingMs, long recallMs, long rerankMs, long totalMs, int contextCharacters,
        int rerankInputCharacters, int rerankAttempts, Long rerankTokens,
        List<Candidate> recalled, List<Candidate> before, List<Candidate> after) {
    public RetrievalTrace { recalled = List.copyOf(recalled); before = List.copyOf(before); after = List.copyOf(after); }

    public record Candidate(long chunkId, long documentId, String filename, int chunkIndex, double vectorScore, Double rerankScore) {
        public static List<Candidate> from(List<RetrievedSourceVO> sources) {
            return sources.stream().map(source -> new Candidate(source.chunkId(), source.documentId(), source.filename(),
                    source.chunkIndex(), source.score(), source.rerankScore())).toList();
        }
    }
}
