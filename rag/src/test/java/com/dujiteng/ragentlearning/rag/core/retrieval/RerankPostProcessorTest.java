package com.dujiteng.ragentlearning.rag.core.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import com.dujiteng.ragentlearning.infra.rerank.RerankService;
import com.dujiteng.ragentlearning.knowledge.vo.RetrievedSourceVO;
import com.dujiteng.ragentlearning.rag.core.retrieval.postprocessor.RerankPostProcessor;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RerankPostProcessorTest {
    private final List<RetrievedSourceVO> candidates = List.of(source(1, "资料一", 0.9), source(2, "资料二", 0.8));

    @Test
    void fewerCandidatesStillGetRerankedAndKeepEverySourceField() {
        var processor = new RerankPostProcessor(new Stub() {
            @Override public RerankResponse rerank(String q, List<String> docs, StreamCancellation cancel) {
                return new RerankResponse(List.of(new Score(1, 0.99), new Score(0, 0.2)), 12L);
            }
        }, 1000);
        var result = processor.process("问题", candidates, true, new StreamCancellation());
        assertEquals("APPLIED", result.status());
        assertEquals(candidates.get(1).withRerankScore(0.99), result.ordered().get(0));
        assertEquals(0.8, result.ordered().get(0).score());
        assertNull(candidates.get(1).rerankScore());
    }

    @Test
    void disableEmptyAndCharacterBudgetDoNotCallModel() {
        var calls = new AtomicInteger();
        var processor = new RerankPostProcessor(new Stub() {
            @Override public RerankResponse rerank(String q, List<String> docs, StreamCancellation cancel) { calls.incrementAndGet(); throw new AssertionError("不应调用"); }
        }, 1000);
        assertEquals("DISABLED", processor.process("问题", candidates, false, new StreamCancellation()).status());
        assertEquals("SKIPPED_EMPTY", processor.process("问题", List.of(), true, new StreamCancellation()).status());
        assertEquals("SKIPPED_BUDGET", processor.process("问题", List.of(source(1, "长".repeat(1001), 0.9)), true, new StreamCancellation()).status());
        assertEquals(0, calls.get());
    }

    @Test
    void knownFailureFallsBackWhileCancellationPropagates() {
        var processor = new RerankPostProcessor(new Stub() {
            @Override public RerankResponse rerank(String q, List<String> docs, StreamCancellation cancel) { throw new ChatApiException(504, "RERANK_TIMEOUT", "超时"); }
        }, 1000);
        var result = processor.process("问题", candidates, true, new StreamCancellation());
        assertEquals("FALLBACK_TIMEOUT", result.status()); assertEquals(candidates, result.ordered());
        var cancellation = new StreamCancellation(); cancellation.cancel();
        assertThrows(StreamCancellation.Cancelled.class, () -> processor.process("问题", candidates, true, cancellation));
    }

    @Test
    void legacySourceJsonAndBudgetInvariantsRemainExplicit() {
        var source = new ObjectMapper().readValue("{\"chunkId\":1,\"documentId\":2,\"filename\":\"old.md\",\"chunkIndex\":0,\"startOffset\":0,\"endOffset\":2,\"content\":\"正文\",\"score\":0.8,\"sourceVersion\":1}", RetrievedSourceVO.class);
        assertNull(source.rerankScore());
        assertEquals(new RetrievalBudget(16, 12, 4).contextTopK(), 4);
        assertThrows(IllegalArgumentException.class, () -> new RetrievalBudget(4, 12, 4));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalBudget(16, 2, 4));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalBudget(101, 12, 4));
    }

    private static RetrievedSourceVO source(long id, String content, double score) {
        return new RetrievedSourceVO(id, id + 10, "资料" + id + ".md", 0, 0, content.length(), content, score, 1, null);
    }
    private abstract static class Stub implements RerankService { @Override public String model() { return "synthetic"; } }
}
