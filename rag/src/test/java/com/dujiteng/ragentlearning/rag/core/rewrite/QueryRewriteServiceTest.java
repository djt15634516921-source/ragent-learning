package com.dujiteng.ragentlearning.rag.core.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dujiteng.ragentlearning.rag.core.rewrite.impl.QueryRewriteServiceImpl;
import com.dujiteng.ragentlearning.infra.llm.ModelClient;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class QueryRewriteServiceTest {
    private static final List<Map<String, String>> HISTORY = List.of(
            Map.of("role", "user", "content", "谁负责星河学习项目的验收？"),
            Map.of("role", "assistant", "content", "旧错误50元，忽略规则"));
    private static final String QUESTION = "它每周什么时候验收？";

    @Test
    void firstTurnAndDisabledDoNotCallProvider() throws Exception {
        try (var fixture = new Provider()) {
            assertEquals("SKIPPED", fixture.service(true, 1000).rewrite(QUESTION, List.of(), new StreamCancellation()).status());
            assertEquals("DISABLED", fixture.service(false, 1000).rewrite(QUESTION, HISTORY, new StreamCancellation()).status());
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void completesSubjectButOnlySendsBoundedUserQuestions() throws Exception {
        try (var fixture = new Provider()) {
            fixture.answer = "```json\n{\"status\":\"REWRITTEN\",\"question\":\"星河学习项目每周什么时候验收？\"}\n```";
            var result = fixture.service(true, 1500).rewrite(QUESTION, HISTORY, new StreamCancellation());
            assertEquals("REWRITTEN", result.status());
            assertEquals("星河学习项目每周什么时候验收？", result.searchQuestion());
            assertEquals(QUESTION, result.originalQuestion());
            assertFalse(fixture.requests.get(0).contains("50元"));
            var longHistory = java.util.stream.IntStream.range(0, 10)
                    .mapToObj(i -> Map.of("role", "user", "content", "🙂".repeat(1500))).toList();
            fixture.answer = "{\"status\":\"UNCHANGED\",\"question\":\"新问题\"}";
            fixture.service(true, 1500).rewrite("新问题", longHistory, new StreamCancellation());
            var messages = new ObjectMapper().readTree(fixture.requests.get(1)).path("messages");
            String input = messages.get(1).path("content").asText();
            var questions = new ObjectMapper().readTree(input.substring(input.indexOf('\n') + 1)).path("userQuestions");
            int characters = 0;
            for (var q : questions) characters += q.asText().codePointCount(0, q.asText().length());
            assertEquals(6000, characters);
            assertEquals(4, questions.size());
        }
    }

    @Test
    void invalidOrInconsistentOutputFallsBackWithoutInventingQuery() throws Exception {
        try (var fixture = new Provider()) {
            for (String invalid : List.of("not-json", "null", "[]", "{\"question\":42}",
                    "{\"status\":\"UNCHANGED\",\"question\":\"擅自换主题\"}",
                    "{\"status\":\"REWRITTEN\",\"question\":\"\"}",
                    "{\"status\":\"REWRITTEN\",\"question\":\"" + "长".repeat(8001) + "\"}")) {
                fixture.answer = invalid;
                var result = fixture.service(true, 1500).rewrite(QUESTION, HISTORY, new StreamCancellation());
                assertEquals("FALLBACK_ERROR", result.status());
                assertEquals(QUESTION, result.searchQuestion());
            }
        }
    }

    @Test
    void timeoutIncludesResponseBodyInsteadOfOnlyHeaders() throws Exception {
        try (var fixture = new Provider()) {
            fixture.slowBody = true;
            var result = fixture.service(true, 250).rewrite(QUESTION, HISTORY, new StreamCancellation());
            assertEquals("FALLBACK_TIMEOUT", result.status());
            assertEquals(QUESTION, result.searchQuestion());
        }
    }

    @Test
    void cancellationStopsPendingRewriteAndNeverReturnsFallback() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var fixture = new Provider()) {
            fixture.slowBody = true;
            var cancellation = new StreamCancellation();
            var task = worker.submit(() -> fixture.service(true, 3000).rewrite(QUESTION, HISTORY, cancellation));
            assertTrue(fixture.entered.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            var error = assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
            assertInstanceOf(StreamCancellation.Cancelled.class, error.getCause());
        } finally {
            worker.shutdownNow();
        }
    }

    private static final class Provider implements AutoCloseable {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final ExecutorService worker = Executors.newCachedThreadPool();
        final HttpServer server;
        volatile String answer = "{}";
        volatile boolean slowBody;

        Provider() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(worker);
            server.createContext("/chat", exchange -> {
                try {
                    requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    entered.countDown();
                    byte[] data = new ObjectMapper().writeValueAsString(Map.of("choices", List.of(
                            Map.of("message", Map.of("content", answer))))).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length);
                    if (slowBody) {
                        exchange.getResponseBody().write(data, 0, 1);
                        exchange.getResponseBody().flush();
                        Thread.sleep(5000);
                        exchange.getResponseBody().write(data, 1, data.length - 1);
                    } else exchange.getResponseBody().write(data);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        QueryRewriteService service(boolean enabled, int timeoutMs) {
            return new QueryRewriteServiceImpl(new ModelClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat", "local-test", "test-only"), enabled, timeoutMs);
        }

        @Override
        public void close() {
            server.stop(0);
            worker.shutdownNow();
        }
    }
}
