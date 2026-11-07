package com.dujiteng.ragentlearning.infra.rerank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BaiLianRerankClientTest {
    @Test
    void nativePayloadAndIndexMappingKeepOriginalDocuments() throws Exception {
        try (var server = new Provider(exchange -> reply(exchange, 200,
                "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.9,\"document\":{\"text\":\"untrusted replacement\"}}]},\"usage\":{\"total_tokens\":42}}"))) {
            var response = server.client(2000).rerank("中文问题🙂", List.of("原始资料一", "原始资料二"), new StreamCancellation());
            assertEquals(1, response.scores().get(0).index());
            assertEquals(0.9, response.scores().get(0).relevance());
            assertEquals(42L, response.totalTokens());
            assertEquals("Bearer synthetic-secret", server.authorization.get(0));
            var request = server.requests.get(0);
            assertEquals("qwen3-rerank", request.get("model"));
            assertEquals("中文问题🙂", ((Map<?, ?>) request.get("input")).get("query"));
            assertEquals(List.of("原始资料一", "原始资料二"), ((Map<?, ?>) request.get("input")).get("documents"));
            assertEquals(2, ((Map<?, ?>) request.get("parameters")).get("top_n"));
            assertEquals(false, ((Map<?, ?>) request.get("parameters")).get("return_documents"));
        }
    }

    @Test
    void rejectsPartialDuplicateOutOfRangeAndNonFiniteResults() throws Exception {
        for (String body : List.of("null", "{}", "{\"output\":{\"results\":[]}}",
                "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.8}]}}",
                "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.8},{\"index\":0,\"relevance_score\":0.7}]}}",
                "{\"output\":{\"results\":[{\"index\":2,\"relevance_score\":0.8},{\"index\":1,\"relevance_score\":0.7}]}}",
                "{\"output\":{\"results\":[{\"index\":0.5,\"relevance_score\":0.8},{\"index\":1,\"relevance_score\":0.7}]}}",
                "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":1e400},{\"index\":1,\"relevance_score\":0.7}]}}")) {
            try (var server = new Provider(exchange -> reply(exchange, 200, body))) {
                var error = assertThrows(ChatApiException.class, () -> server.client(2000).rerank("问题", List.of("资料一", "资料二"), new StreamCancellation()));
                assertEquals("RERANK_RESPONSE_INVALID", error.code());
            }
        }
    }

    @Test
    void normalizesHttpFailuresWithoutExposingUpstreamBody() throws Exception {
        for (int status : List.of(401, 429, 500)) {
            try (var server = new Provider(exchange -> reply(exchange, status, "synthetic-secret 完整的私有问题 原始资料"))) {
                var error = assertThrows(ChatApiException.class, () -> server.client(2000).rerank("完整的私有问题", List.of("原始资料"), new StreamCancellation()));
                assertEquals(status == 401 ? "RERANK_AUTH_FAILED" : status == 429 ? "RERANK_RATE_LIMITED" : "RERANK_HTTP_ERROR", error.code());
                assertFalse(error.getMessage().contains("synthetic-secret"));
                assertFalse(error.getMessage().contains("私有问题"));
                assertEquals(1, server.requests.size());
            }
        }
    }

    @Test
    void deadlineCoversCompleteBodyAndDoesNotRetry() throws Exception {
        try (var server = new Provider(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
            Thread.sleep(700);
        })) {
            var error = assertThrows(ChatApiException.class, () -> server.client(200).rerank("问题", List.of("正文"), new StreamCancellation()));
            assertEquals("RERANK_TIMEOUT", error.code());
            assertEquals(1, server.requests.size());
        }
    }

    @Test
    void cancellationPropagatesAndClosesPendingRequest() throws Exception {
        var entered = new CountDownLatch(1);
        try (var server = new Provider(exchange -> { entered.countDown(); Thread.sleep(1500); })) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                var cancellation = new StreamCancellation();
                var request = executor.submit(() -> server.client(3000).rerank("问题", List.of("正文"), cancellation));
                assertTrue(entered.await(2, TimeUnit.SECONDS)); cancellation.cancel();
                var error = assertThrows(ExecutionException.class, () -> request.get(1, TimeUnit.SECONDS));
                assertTrue(error.getCause() instanceof StreamCancellation.Cancelled);
                assertEquals(1, server.requests.size());
            } finally { executor.shutdownNow(); }
        }
    }

    @Test
    void boundsResponseAndTreatsMissingConfigurationExplicitly() throws Exception {
        try (var server = new Provider(exchange -> reply(exchange, 200, "x".repeat(300000)))) {
            var error = assertThrows(ChatApiException.class, () -> server.client(2000).rerank("问题", List.of("正文"), new StreamCancellation()));
            assertEquals("RERANK_RESPONSE_INVALID", error.code());
        }
        var client = new BaiLianRerankClient("", "qwen3-rerank", "", 1000);
        assertEquals("RERANK_NOT_CONFIGURED", assertThrows(ChatApiException.class,
                () -> client.rerank("问题", List.of("正文"), new StreamCancellation())).code());
    }

    private static void reply(HttpExchange exchange, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }

    private interface Reply { void send(HttpExchange exchange) throws Exception; }
    private static final class Provider implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        private final List<String> authorization = new CopyOnWriteArrayList<>();
        private Provider(Reply reply) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/rerank", exchange -> {
                try {
                    var mapper = new ObjectMapper();
                    @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                    requests.add(payload); authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    reply.send(exchange);
                } catch (Exception ignored) { /* 超时/取消可能先关闭接收端。 */ }
                finally { exchange.close(); }
            });
            server.start();
        }
        private BaiLianRerankClient client(int timeout) { return new BaiLianRerankClient("http://127.0.0.1:" + server.getAddress().getPort() + "/rerank", "qwen3-rerank", "synthetic-secret", timeout); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
