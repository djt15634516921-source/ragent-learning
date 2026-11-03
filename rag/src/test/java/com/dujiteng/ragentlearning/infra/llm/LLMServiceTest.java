package com.dujiteng.ragentlearning.infra.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LLMServiceTest {
    @Test
    void requestOptionsAreLocalAndLegacyEntryUsesSameProtocol() throws Exception {
        try (var provider = new Provider()) {
            var request = LLMRequest.of("测试规则", List.of(), "原问题");
            assertEquals("完成", provider.client.chat(request.withOptions(1200, 0.0, 512), new StreamCancellation()));
            assertEquals("完成", provider.client.chat("测试规则", List.of(), "原问题"));
            var shortInput = provider.requests.get(0);
            var answerInput = provider.requests.get(1);
            assertEquals(512, shortInput.get("max_tokens"));
            assertFalse(answerInput.containsKey("max_tokens"));
            assertFalse(answerInput.containsKey("temperature"));
            assertEquals(shortInput.get("messages"), answerInput.get("messages"));
            assertFalse((boolean) shortInput.get("stream"));
            assertEquals(60_000, request.timeoutMs());
        }
    }

    @Test
    void responseLimitRemainsSpecificToTheCallingTask() throws Exception {
        try (var provider = new Provider()) {
            provider.response = new ObjectMapper().writeValueAsString(Map.of("choices", List.of(
                    Map.of("message", Map.of("content", "完成"))), "unused", "x".repeat(1000)));
            var request = LLMRequest.of("规则", List.of(), "问题");
            var error = assertThrows(ChatApiException.class,
                    () -> provider.client.chat(request.withResponseLimit(100), new StreamCancellation()));
            assertEquals("MODEL_RESPONSE_UNREADABLE", error.code());
            assertEquals("完成", provider.client.chat(request, new StreamCancellation()));
        }
    }

    @Test
    void modelErrorsKeepCodesAndDoNotExposeKeyOrFullPrompt() throws Exception {
        try (var provider = new Provider()) {
            var request = LLMRequest.of("私有系统提示", List.of(), "私有完整问题");
            for (var entry : Map.of(401, "MODEL_AUTH_FAILED", 429, "MODEL_RATE_LIMITED", 503, "MODEL_HTTP_ERROR").entrySet()) {
                provider.status = entry.getKey();
                provider.response = new ObjectMapper().writeValueAsString(Map.of("error", Map.of("message",
                        "test-only-key 私有系统提示 私有完整问题 平台提示")));
                var error = assertThrows(ChatApiException.class,
                        () -> provider.client.chat(request, new StreamCancellation()));
                assertEquals(entry.getValue(), error.code());
                assertFalse(error.getMessage().contains("test-only-key"));
                assertFalse(error.getMessage().contains("私有系统提示"));
                assertFalse(error.getMessage().contains("私有完整问题"));
            }
        }
    }

    @Test
    void concurrentShortDeadlineDoesNotChangeOtherRequestsBudget() throws Exception {
        var callers = Executors.newSingleThreadExecutor();
        try (var provider = new Provider()) {
            provider.delayMs = 650;
            var answer = callers.submit(() -> provider.client.chat(
                    LLMRequest.of("规则", List.of(), "回答").withOptions(2500, null, null), new StreamCancellation()));
            var error = assertThrows(ChatApiException.class, () -> provider.client.chat(
                    LLMRequest.of("规则", List.of(), "改写").withOptions(250, 0.0, 512), new StreamCancellation()));
            assertEquals("MODEL_CONNECTION_FAILED", error.code());
            assertNotNull(error.getCause());
            assertTrue(error.getCause() instanceof java.net.http.HttpTimeoutException);
            assertEquals("完成", answer.get(3, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void streamUsesSameMessagesAndExistingSseParser() throws Exception {
        try (var provider = new Provider()) {
            provider.sse = true;
            List<String> deltas = new ArrayList<>();
            assertEquals("你好", provider.client.streamChat(LLMRequest.of("规则", List.of(), "问题"), deltas::add,
                    new StreamCancellation()));
            assertEquals(List.of("你好"), deltas);
            assertEquals(true, provider.requests.get(0).get("stream"));
        }
    }

    private static final class Provider implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool();
        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        final ModelClient client;
        volatile int status = 200;
        volatile int delayMs;
        volatile boolean sse;
        volatile String response = "{\"choices\":[{\"message\":{\"content\":\"完成\"}}]}";

        Provider() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/chat", exchange -> {
                try {
                    var json = new ObjectMapper();
                    requests.add(json.readValue(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                            new tools.jackson.core.type.TypeReference<Map<String, Object>>() { }));
                    if (delayMs > 0) Thread.sleep(delayMs);
                    String data = sse ? "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n" : response;
                    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", sse ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            client = new ModelClient("http://127.0.0.1:" + server.getAddress().getPort() + "/chat", "local-test", "test-only-key");
        }

        @Override
        public void close() {
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
