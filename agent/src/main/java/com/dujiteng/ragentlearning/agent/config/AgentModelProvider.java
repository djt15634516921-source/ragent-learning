package com.dujiteng.ragentlearning.agent.config;

import com.dujiteng.ragentlearning.common.ChatApiException;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 复用应用的模型连接配置，工具协议由 AgentScope 的适配器处理。 */
@Component
public class AgentModelProvider {
    private final String chatUrl;
    private final String modelName;
    private final String apiKey;
    private OpenAIChatModel model;

    public AgentModelProvider(@Value("${llm.chat-url:}") String chatUrl,
                              @Value("${llm.model:}") String modelName,
                              @Value("${llm.api-key:}") String apiKey) {
        this.chatUrl = chatUrl.strip();
        this.modelName = modelName.strip();
        this.apiKey = apiKey.strip();
    }

    public synchronized OpenAIChatModel getModel() {
        if (model != null) {
            return model;
        }
        if (chatUrl.isEmpty() || modelName.isEmpty() || apiKey.isEmpty()) {
            throw new ChatApiException(503, "AGENT_MODEL_NOT_CONFIGURED", "请配置现有聊天模型的地址、模型名和密钥");
        }
        URI endpoint;
        try {
            endpoint = URI.create(chatUrl);
        } catch (IllegalArgumentException invalidUrl) {
            throw invalidEndpoint();
        }
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || (!"https".equalsIgnoreCase(endpoint.getScheme()) && !"http".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getRawPath().isBlank() || "/".equals(endpoint.getRawPath())) {
            throw invalidEndpoint();
        }
        String path = endpoint.getRawPath();
        if (endpoint.getRawQuery() != null) {
            path += "?" + endpoint.getRawQuery();
        }
        var builder = OpenAIChatModel.builder()
                .baseUrl(endpoint.getScheme() + "://" + endpoint.getRawAuthority())
                .endpointPath(path)
                .modelName(modelName)
                .apiKey(apiKey)
                .stream(false)
                .generateOptions(GenerateOptions.builder().maxTokens(2048).build())
                .nativeStructuredOutputWithTools(false);
        if ("api.deepseek.com".equalsIgnoreCase(endpoint.getHost())) {
            builder.formatter(new DeepSeekFormatter());
        }
        model = builder.build();
        return model;
    }

    private static ChatApiException invalidEndpoint() {
        return new ChatApiException(503, "AGENT_MODEL_URL_INVALID", "请将聊天模型地址配置为完整的 HTTP(S) Chat Completions 接口");
    }
}
