package com.dujiteng.ragentlearning.infra.llm;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 模型适配层：封装 HTTP 地址、鉴权、请求格式和响应解析。 */
@Component
public class ModelClient {

    private final String chatUrl;
    private final String model;
    private final String apiKey;
    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public ModelClient(
            @Value("${llm.chat-url:}") String chatUrl,
            @Value("${llm.model:}") String model,
            @Value("${llm.api-key:}") String apiKey) {
        this.chatUrl = chatUrl.strip();
        this.model = model.strip();
        this.apiKey = apiKey.strip();

        // 连接超时与等待响应超时分开设置，避免请求无限等待。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public String chat(String systemPrompt, List<Map<String, String>> history, String question) {
        URI endpoint = configuredEndpoint();

        List<Map<String, String>> messages = messages(systemPrompt, history, question);

        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", messages,
                "stream", false);

        try {
            Map<String, Object> response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            return ModelResponseParser.answer(response);
        } catch (RestClientResponseException error) {
            // 向调用者返回可定位的简短错误，不原样返回平台响应或密钥。
            int status = error.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new ChatApiException(502, "MODEL_AUTH_FAILED",
                        "模型平台鉴权或权限检查失败，请检查 Key、平台、地域及模型访问权限", error);
            }
            if (status == 429) {
                throw new ChatApiException(503, "MODEL_RATE_LIMITED",
                        "模型平台返回 429，请检查调用配额、并发或限流提示", error);
            }
            String detail = safeProviderMessage(error, messages);
            throw new ChatApiException(502, "MODEL_HTTP_ERROR",
                    "模型平台返回 HTTP " + status + "，请检查调用地址、模型名称及请求参数"
                            + (detail.isBlank() ? "" : "；平台提示：" + detail), error);
        } catch (ResourceAccessException error) {
            throw new ChatApiException(502, "MODEL_CONNECTION_FAILED",
                    "连接模型平台失败或等待超时，请检查调用地址、网络及模型响应时间", error);
        } catch (RestClientException error) {
            throw new ChatApiException(502, "MODEL_RESPONSE_UNREADABLE",
                    "模型响应无法按 JSON 解析，请确认地址指向 Chat Completions API", error);
        }
    }

    /** 与普通 chat 共用消息格式；读取过程在后台线程中同步执行。 */
    public String stream(String systemPrompt, List<Map<String, String>> history, String question,
                         Consumer<String> onDelta, StreamCancellation cancellation) {
        cancellation.check();
        URI endpoint = configuredEndpoint();
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", messages(systemPrompt, history, question),
                "stream", true);
        try {
            return restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .body(payload)
                    .exchange((request, response) -> {
                        cancellation.check();
                        int status = response.getStatusCode().value();
                        // exchange 不使用 retrieve 的自动错误处理，需要自行检查状态。
                        if (status < 200 || status >= 300) {
                            throw streamHttpError(status);
                        }
                        MediaType type = response.getHeaders().getContentType();
                        if (type == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)) {
                            throw new ChatApiException(502, "MODEL_STREAM_CONTENT_TYPE_INVALID",
                                    "模型没有返回 SSE，请确认接口支持 stream=true");
                        }
                        InputStream body = response.getBody();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(body, StandardCharsets.UTF_8))) {
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

    private Map<String, Object> decodeEvent(String json) {
        return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
    }

    private static List<Map<String, String>> messages(String systemPrompt,
                                                      List<Map<String, String>> history, String question) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.addAll(history);
        messages.add(Map.of("role", "user", "content", question));
        return messages;
    }

    private static ChatApiException streamHttpError(int status) {
        if (status == 401 || status == 403) {
            return new ChatApiException(502, "MODEL_AUTH_FAILED", "模型平台鉴权或权限检查失败，请检查现有模型配置");
        }
        if (status == 429) {
            return new ChatApiException(503, "MODEL_RATE_LIMITED", "模型平台限流，请稍后重试或检查额度");
        }
        return new ChatApiException(502, "MODEL_HTTP_ERROR", "模型平台返回 HTTP " + status + "，请检查调用地址和模型名称");
    }

    /** 只提取平台错误中的 message，遮住密钥和完整对话内容后限制长度。 */
    private String safeProviderMessage(RestClientResponseException error, List<Map<String, String>> messages) {
        try {
            Map<String, Object> body = error.getResponseBodyAs(
                    new ParameterizedTypeReference<Map<String, Object>>() { });
            if (body == null) {
                return "";
            }
            Object raw = body.get("message");
            if (body.get("error") instanceof Map<?, ?> nested) {
                raw = nested.get("message");
            }
            if (!(raw instanceof String text)) {
                return "";
            }
            String safe = text.replace(apiKey, "[密钥已隐藏]");
            for (Map<String, String> message : messages) {
                String content = message.get("content");
                if (content != null && !content.isBlank()) {
                    safe = safe.replace(content, "[对话内容已隐藏]");
                }
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
                    "请配置 LLM_CHAT_URL、LLM_MODEL 和 LLM_API_KEY；配置后重新启动应用");
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
                    "LLM_CHAT_URL 应为完整的 HTTP(S) 接口地址，例如以 /v1/chat/completions 结尾", error);
        }
    }
}
