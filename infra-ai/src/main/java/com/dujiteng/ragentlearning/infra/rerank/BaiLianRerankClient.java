package com.dujiteng.ragentlearning.infra.rerank;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.infra.llm.StreamCancellation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 百炼原生text-rerank协议。只发送一次请求，等待完整响应期间可取消。 */
@Component
public class BaiLianRerankClient implements RerankService {
    private static final int MAX_RESPONSE_BYTES = 262144;
    private final String url;
    private final String model;
    private final String apiKey;
    private final int timeoutMs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public BaiLianRerankClient(@Value("${rerank.url:}") String url,
                              @Value("${rerank.model:qwen3-rerank}") String model,
                              @Value("${rerank.api-key:}") String apiKey,
                              @Value("${rerank.timeout-ms:3000}") int timeoutMs) {
        if (timeoutMs < 100 || timeoutMs > 30000) throw new IllegalArgumentException("重排超时须为100～30000毫秒");
        this.url = url.strip();
        this.model = model.strip();
        this.apiKey = apiKey.strip();
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String model() { return model; }

    @Override
    public RerankResponse rerank(String query, List<String> documents, StreamCancellation cancellation) {
        cancellation.check();
        if (documents.isEmpty()) return new RerankResponse(List.of(), null);
        if (query == null || query.isBlank() || query.length() > 8000 || documents.size() > 100
                || documents.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new ChatApiException(400, "RERANK_INPUT_INVALID", "重排问题或候选文本不符合限制");
        }
        URI endpoint = endpoint();
        var payload = Map.of("model", model, "input", Map.of("query", query, "documents", List.copyOf(documents)),
                "parameters", Map.of("top_n", documents.size(), "return_documents", false));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
        var pending = http.sendAsync(request, info -> new LimitedBodySubscriber());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (true) {
                cancellation.check();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw timeout(null);
                try {
                    var response = pending.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                    cancellation.check();
                    int status = response.statusCode();
                    if (status == 401 || status == 403) throw new ChatApiException(502, "RERANK_AUTH_FAILED", "重排服务鉴权失败，请检查模型权限、地域和密钥");
                    if (status == 429) throw new ChatApiException(503, "RERANK_RATE_LIMITED", "重排服务限流，本次保留向量排序");
                    if (status < 200 || status >= 300) throw new ChatApiException(502, "RERANK_HTTP_ERROR", "重排服务返回HTTP " + status);
                    return decode(response.body(), documents.size());
                } catch (TimeoutException waiting) {
                    // 轮询同一个Future以观察取消，不重发HTTP请求。
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StreamCancellation.Cancelled();
        } catch (ExecutionException failed) {
            cancellation.check();
            for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException) throw timeout(failed);
                if (cause instanceof ResponseTooLarge) throw invalid(failed);
            }
            throw new ChatApiException(502, "RERANK_CONNECTION_FAILED", "重排服务连接失败", failed);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private RerankResponse decode(byte[] body, int count) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) throw invalid(null);
            JsonNode results = root.path("output").path("results");
            if (!results.isArray() || results.size() != count) throw invalid(null);
            var seen = new HashSet<Integer>();
            List<Score> scores = new ArrayList<>();
            for (var item : results) {
                JsonNode index = item.path("index"), relevance = item.path("relevance_score");
                if (!index.isIntegralNumber() || !index.canConvertToInt() || !relevance.isNumber()) throw invalid(null);
                int position = index.intValue();
                double score = relevance.doubleValue();
                if (position < 0 || position >= count || !seen.add(position) || !Double.isFinite(score) || score < 0 || score > 1) throw invalid(null);
                scores.add(new Score(position, score));
            }
            scores.sort(Comparator.comparingDouble(Score::relevance).reversed().thenComparingInt(Score::index));
            JsonNode usage = root.path("usage").path("total_tokens");
            Long tokens = usage.isIntegralNumber() && usage.canConvertToLong() && usage.longValue() >= 0 ? usage.longValue() : null;
            // 只返回索引和分数，忽略上游回传正文，来源始终取本地候选快照。
            return new RerankResponse(scores, tokens);
        } catch (JacksonException | IllegalArgumentException error) {
            throw invalid(error);
        }
    }

    private URI endpoint() {
        if (url.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw new ChatApiException(503, "RERANK_NOT_CONFIGURED", "请配置RERANK_URL及模型密钥后启用重排");
        }
        try {
            URI endpoint = URI.create(url);
            if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("invalid endpoint");
            }
            return endpoint;
        } catch (IllegalArgumentException error) {
            throw new ChatApiException(503, "RERANK_URL_INVALID", "重排地址须为不含凭证或查询参数的完整HTTP(S)接口", error);
        }
    }

    private static ChatApiException timeout(Throwable cause) {
        return new ChatApiException(504, "RERANK_TIMEOUT", "等待重排完整响应超时", cause);
    }

    private static ChatApiException invalid(Throwable cause) {
        return new ChatApiException(502, "RERANK_RESPONSE_INVALID", "重排结果缺失或索引/分数无效，本次保留向量排序", cause);
    }

    private static final class ResponseTooLarge extends IOException { }

    /** 在收集阶段限制字节数，避免等整个错误正文进入内存后再做长度检查。 */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;
        private boolean failed;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (var buffer : buffers) bytes += buffer.remaining();
            if (bytes > MAX_RESPONSE_BYTES) {
                failed = true; subscription.cancel(); delegate.onError(new ResponseTooLarge());
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!failed) { failed = true; delegate.onError(error); } }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
