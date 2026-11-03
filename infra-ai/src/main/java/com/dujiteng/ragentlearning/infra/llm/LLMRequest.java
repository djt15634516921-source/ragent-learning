package com.dujiteng.ragentlearning.infra.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 模型协议请求；不包含用户归属、知识库或改写状态等业务概念。 */
public record LLMRequest(List<Map<String, String>> messages, int timeoutMs, Double temperature, Integer maxTokens,
                         int maxResponseChars) {
    public LLMRequest {
        if (messages == null || messages.isEmpty() || timeoutMs <= 0 || maxResponseChars < 0
                || (temperature != null && (!Double.isFinite(temperature) || temperature < 0))
                || (maxTokens != null && maxTokens <= 0)) {
            throw new IllegalArgumentException("模型请求参数不正确");
        }
        messages = messages.stream().map(Map::copyOf).toList();
    }

    public static LLMRequest of(String systemPrompt, List<Map<String, String>> history, String question) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.addAll(history);
        messages.add(Map.of("role", "user", "content", question));
        return new LLMRequest(messages, 60_000, null, null, 0);
    }

    public LLMRequest withOptions(int timeoutMs, Double temperature, Integer maxTokens) {
        return new LLMRequest(messages, timeoutMs, temperature, maxTokens, maxResponseChars);
    }

    public LLMRequest withResponseLimit(int characters) {
        return new LLMRequest(messages, timeoutMs, temperature, maxTokens, characters);
    }
}
