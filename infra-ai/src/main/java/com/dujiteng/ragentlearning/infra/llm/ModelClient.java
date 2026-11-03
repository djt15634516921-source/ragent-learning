package com.dujiteng.ragentlearning.infra.llm;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 当前唯一的Chat Completions协议适配器，回答与改写共享鉴权、参数及响应解析。 */
@Component
public class ModelClient implements LLMService {
    private final String chatUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public ModelClient(@Value("${llm.chat-url:}") String chatUrl,
                       @Value("${llm.model:}") String model,
                       @Value("${llm.api-key:}") String apiKey) {
        this.chatUrl = chatUrl.strip();
        this.model = model.strip();
        this.apiKey = apiKey.strip();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 保留原调用签名；业务新代码统一依赖LLMService。 */
    public String chat(String systemPrompt, List<Map<String, String>> history, String question) {
        return chat(LLMRequest.of(systemPrompt, history, question), new StreamCancellation());
    }

    @Override
    public String chat(LLMRequest input, StreamCancellation cancellation) {
        cancellation.check();
        URI endpoint = configuredEndpoint();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(input.timeoutMs()))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(payload(input, false)),
                        StandardCharsets.UTF_8)).build();
        var pending = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(input.timeoutMs());
        try {
            while (true) {
                cancellation.check();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw connectionFailure(new HttpTimeoutException("模型调用等待超时"));
                try {
                    var response = pending.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                    cancellation.check();
                    if (input.maxResponseChars() > 0 && response.body().length() > input.maxResponseChars()) {
                        throw new ChatApiException(502, "MODEL_RESPONSE_UNREADABLE", "模型响应超过本次调用的长度预算");
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw httpError(response.statusCode(), response.body(), input.messages());
                    }
                    Map<String, Object> data = jsonMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
                    return ModelResponseParser.answer(data);
                } catch (TimeoutException waiting) {
                    // 等待同一个HTTP请求；短间隔用于及时观察取消，不重复调用模型。
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancellation.check();
            throw new StreamCancellation.Cancelled();
        } catch (ExecutionException failed) {
            cancellation.check();
            throw connectionFailure(failed.getCause());
        } catch (JacksonException unreadable) {
            throw new ChatApiException(502, "MODEL_RESPONSE_UNREADABLE",
                    "模型响应无法按JSON解析，请确认地址指向Chat Completions API", unreadable);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    /** 保留原流式签名及读取协议。 */
    public String stream(String systemPrompt, List<Map<String, String>> history, String question,
                         Consumer<String> onDelta, StreamCancellation cancellation) {
        return streamChat(LLMRequest.of(systemPrompt, history, question), onDelta, cancellation);
    }

    @Override
    public String streamChat(LLMRequest input, Consumer<String> onDelta, StreamCancellation cancellation) {
        cancellation.check();
        URI endpoint = configuredEndpoint();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // 每次请求自己的等待配置，改写的短预算不会修改共享客户端的回答预算。
        factory.setReadTimeout(Duration.ofMillis(input.timeoutMs()));
        RestClient restClient = RestClient.builder().requestFactory(factory).build();
        try {
            return restClient.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .body(payload(input, true))
                    .exchange((request, response) -> {
                        cancellation.check();
                        int status = response.getStatusCode().value();
                        if (status < 200 || status >= 300) throw httpError(status, null, input.messages());
                        MediaType type = response.getHeaders().getContentType();
                        if (type == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)) {
                            throw new ChatApiException(502, "MODEL_STREAM_CONTENT_TYPE_INVALID",
                                    "模型没有返回SSE，请确认接口支持stream=true");
                        }
                        InputStream body = response.getBody();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                            cancellation.attach(body);
                            try {
                                return ModelStreamParser.read(reader, this::decodeEvent, onDelta, cancellation);
                            } finally {
                                cancellation.detach(body);
                            }
                        }
                    });
        } catch (ResourceAccessException error) {
            cancellation.check();
            throw new ChatApiException(502, "MODEL_CONNECTION_FAILED",
                    "模型流连接失败或读取中断；未完成的这一轮不会保存", error);
        } catch (RestClientException error) {
            cancellation.check();
            throw new ChatApiException(502, "MODEL_RESPONSE_UNREADABLE",
                    "无法读取模型流，请确认调用地址与平台支持的协议", error);
        }
    }

    private Map<String, Object> payload(LLMRequest input, boolean stream) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
        result.put("messages", input.messages());
        result.put("stream", stream);
        if (input.temperature() != null) result.put("temperature", input.temperature());
        if (input.maxTokens() != null) result.put("max_tokens", input.maxTokens());
        return result;
    }

    private Map<String, Object> decodeEvent(String json) {
        return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
    }

    private static ChatApiException connectionFailure(Throwable error) {
        return new ChatApiException(502, "MODEL_CONNECTION_FAILED",
                "连接模型平台失败或等待超时，请检查调用地址、网络及模型响应时间", error);
    }

    private ChatApiException httpError(int status, String body, List<Map<String, String>> messages) {
        if (status == 401 || status == 403) {
            return new ChatApiException(502, "MODEL_AUTH_FAILED",
                    "模型平台鉴权或权限检查失败，请检查Key、平台、地域及模型访问权限");
        }
        if (status == 429) {
            return new ChatApiException(503, "MODEL_RATE_LIMITED",
                    "模型平台返回429，请检查调用配额、并发或限流提示");
        }
        String detail = safeProviderMessage(body, messages);
        return new ChatApiException(502, "MODEL_HTTP_ERROR",
                "模型平台返回HTTP " + status + "，请检查调用地址、模型名称及请求参数"
                        + (detail.isBlank() ? "" : "；平台提示：" + detail));
    }

    /** 仅提取有限的平台提示，遮住密钥及完整对话，不输出原始错误正文。 */
    private String safeProviderMessage(String rawBody, List<Map<String, String>> messages) {
        if (rawBody == null || rawBody.isBlank()) return "";
        try {
            Map<String, Object> body = jsonMapper.readValue(rawBody, new TypeReference<Map<String, Object>>() { });
            if (body == null) return "";
            Object raw = body.get("message");
            if (body.get("error") instanceof Map<?, ?> nested) raw = nested.get("message");
            if (!(raw instanceof String text)) return "";
            String safe = text.replace(apiKey, "[密钥已隐藏]");
            for (Map<String, String> message : messages) {
                String content = message.get("content");
                if (content != null && !content.isBlank()) safe = safe.replace(content, "[对话内容已隐藏]");
            }
            safe = safe.replace('\r', ' ').replace('\n', ' ').strip();
            return safe.substring(0, Math.min(safe.length(), 400));
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    private URI configuredEndpoint() {
        if (chatUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            throw new ChatApiException(503, "MODEL_CONFIG_MISSING",
                    "请配置LLM_CHAT_URL、LLM_MODEL和LLM_API_KEY；配置后重新启动应用");
        }
        try {
            URI endpoint = URI.create(chatUrl);
            String scheme = endpoint.getScheme();
            if ((!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)))
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid endpoint");
            }
            return endpoint;
        } catch (IllegalArgumentException error) {
            throw new ChatApiException(503, "MODEL_URL_INVALID",
                    "LLM_CHAT_URL应为完整的HTTP(S)接口地址，例如以/v1/chat/completions结尾", error);
        }
    }
}
