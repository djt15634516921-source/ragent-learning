package com.dujiteng.ragentlearning.rag.core.retrieval.postprocessor;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import com.dujiteng.ragentlearning.infra.rerank.RerankService;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RerankPostProcessor {
    private static final System.Logger LOG = System.getLogger(RerankPostProcessor.class.getName());
    private final RerankService rerank;
    private final int maxInputCharacters;

    public RerankPostProcessor(RerankService rerank, @Value("${rag.rerank.max-input-chars:24000}") int maxInputCharacters) {
        if (maxInputCharacters < 1000 || maxInputCharacters > 200000) throw new IllegalArgumentException("重排字符预算须为1000～200000");
        this.rerank = rerank;
        this.maxInputCharacters = maxInputCharacters;
    }

    public String model() { return rerank.model(); }
    public int maxInputCharacters() { return maxInputCharacters; }

    public Outcome process(String query, List<RetrievedSourceVO> candidates, boolean enabled, StreamCancellation cancellation) {
        cancellation.check();
        int inputCharacters = candidates.stream().mapToInt(source -> source.content().codePointCount(0, source.content().length())
                + query.codePointCount(0, query.length())).sum();
        if (candidates.isEmpty()) return outcome(candidates, "SKIPPED_EMPTY", null, 0, inputCharacters, 0, null);
        if (!enabled) return outcome(candidates, "DISABLED", null, 0, inputCharacters, 0, null);
        if (inputCharacters > maxInputCharacters) return outcome(candidates, "SKIPPED_BUDGET", "RERANK_INPUT_BUDGET", 0, inputCharacters, 0, null);
        long started = System.nanoTime();
        try {
            var response = rerank.rerank(query, candidates.stream().map(RetrievedSourceVO::content).toList(), cancellation);
            cancellation.check();
            var ordered = response.scores().stream().map(score -> candidates.get(score.index()).withRerankScore(score.relevance())).toList();
            return outcome(ordered, "APPLIED", null, elapsed(started), inputCharacters, 1, response.totalTokens());
        } catch (ChatApiException error) {
            if (!error.code().startsWith("RERANK_")) throw error;
            cancellation.check();
            String status = "RERANK_TIMEOUT".equals(error.code()) ? "FALLBACK_TIMEOUT"
                    : "RERANK_NOT_CONFIGURED".equals(error.code()) ? "FALLBACK_NOT_CONFIGURED" : "FALLBACK_ERROR";
            LOG.log(System.Logger.Level.WARNING, "重排回退，错误码 {0}，候选数 {1}", error.code(), candidates.size());
            return outcome(candidates, status, error.code(), elapsed(started), inputCharacters,
                    "RERANK_NOT_CONFIGURED".equals(error.code()) ? 0 : 1, null);
        }
    }

    private Outcome outcome(List<RetrievedSourceVO> sources, String status, String code, long ms, int chars, int attempts, Long tokens) {
        return new Outcome(List.copyOf(sources), status, rerank.model(), code, ms, chars, attempts, tokens);
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    public record Outcome(List<RetrievedSourceVO> ordered, String status, String model, String errorCode,
                          long durationMs, int inputCharacters, int attempts, Long totalTokens) { }
}
