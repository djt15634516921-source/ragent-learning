package com.dujiteng.ragentlearning.infra.embedding;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 文档和问题必须使用同一个向量空间；不把聊天模型的文本当作向量。 */
@Component
public class EmbeddingClient {
    public static final int DIMENSIONS = 1024;
    public static final int BATCH_SIZE = 10;
    private final String url;
    private final String model;
    private final String key;
    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();

    public EmbeddingClient(@Value("${embedding.url:}") String url,
                           @Value("${llm.chat-url:}") String chatUrl,
                           @Value("${embedding.model:text-embedding-v4}") String model,
                           @Value("${embedding.api-key:}") String key) {
        this.url = url.isBlank() ? chatUrl.replaceFirst("/chat/completions/?$", "/embeddings") : url;
        this.model = model;
        this.key = key;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public String model() { return model; }

    public String profile() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((url + "|" + model + "|" + DIMENSIONS).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public List<String> embed(List<String> texts) {
        if (url.isBlank() || !url.endsWith("/embeddings") || key.isBlank() || model.isBlank()) {
            throw new ChatApiException(503, "EMBEDDING_NOT_CONFIGURED", "请配置 Embedding 地址、模型和密钥");
        }
        if (texts.isEmpty() || texts.size() > BATCH_SIZE) throw new IllegalArgumentException("Embedding 批次须为 1～10 条");
        try {
            String body = client.post().uri(url).header("Authorization", "Bearer " + key)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "input", texts, "dimensions", DIMENSIONS, "encoding_format", "float"))
                    .retrieve().body(String.class);
            JsonNode data = json.readTree(body).path("data");
            if (!data.isArray() || data.size() != texts.size()) throw invalid();
            List<String> result = new ArrayList<>(java.util.Collections.nCopies(texts.size(), null));
            for (JsonNode item : data) {
                JsonNode index = item.path("index");
                int position = index.asInt(-1);
                JsonNode vector = item.path("embedding");
                if (!index.isIntegralNumber() || position < 0 || position >= texts.size()
                        || result.get(position) != null || !vector.isArray() || vector.size() != DIMENSIONS) throw invalid();
                double norm = 0;
                for (JsonNode number : vector) {
                    double value = number.asDouble(Double.NaN);
                    if (!number.isNumber() || !Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) throw invalid();
                    norm += value * value;
                }
                if (!Double.isFinite(norm) || norm == 0) throw invalid();
                result.set(position, vector.toString());
            }
            return List.copyOf(result);
        } catch (ChatApiException error) { throw error;
        } catch (RestClientResponseException error) {
            throw new ChatApiException(502, "EMBEDDING_HTTP_FAILED", "向量服务请求失败（HTTP " + error.getStatusCode().value() + "），请检查配置或额度");
        } catch (ResourceAccessException error) {
            throw new ChatApiException(502, "EMBEDDING_CONNECTION_FAILED", "向量服务连接失败或超时");
        } catch (RuntimeException error) { throw invalid(); }
    }

    private static ChatApiException invalid() {
        return new ChatApiException(502, "EMBEDDING_INVALID_RESPONSE", "向量服务返回的数据或维度不正确，预期 1024 维");
    }
}
