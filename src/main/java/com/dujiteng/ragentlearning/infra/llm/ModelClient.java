package com.dujiteng.ragentlearning.infra.llm;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 模型适配层：封装 HTTP 地址、鉴权、请求格式和响应解析。 */
@Component
public class ModelClient {

    private final String chatUrl;
    private final String model;
    private final String apiKey;
    private final RestClient restClient;

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

    public String chat(String systemPrompt, String question) {
        URI endpoint = configuredEndpoint();

        // 这一版每次只发送当前问题，不包含历史、检索资料或工具定义。
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", systemPrompt
                        ),
                        Map.of(
                                "role", "user",
                                "content", question
                        )
                ),
                "stream", false
        );

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
            String detail = safeProviderMessage(error, question);
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

    /** 只提取平台错误中的 message，遮住本次密钥与完整问题后限制长度。 */
    private String safeProviderMessage(RestClientResponseException error, String question) {
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
            String safe = text.replace(apiKey, "[密钥已隐藏]")
                    .replace(question, "[用户问题已隐藏]")
                    .replace('\r', ' ').replace('\n', ' ').strip();
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
