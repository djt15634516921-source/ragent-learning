package com.dujiteng.ragentlearning.infra.llm;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.util.List;
import java.util.Map;

/** 从非流式 Chat Completions 响应中取出最终回答。 */
final class ModelResponseParser {

    private ModelResponseParser() {
    }

    static String answer(Map<String, Object> response) {
        if (response == null
                || !(response.get("choices") instanceof List<?> choices)
                || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) {
            throw new ChatApiException(502, "MODEL_RESPONSE_INVALID",
                    "模型响应缺少 choices[0].message，请确认使用了非流式 Chat Completions 接口");
        }
        Object content = message.get("content");
        if (!(content instanceof String text) || text.isBlank()) {
            throw new ChatApiException(502, "MODEL_ANSWER_EMPTY",
                    "模型没有返回非空的文本回答，请检查模型及其推理/生成参数");
        }
        return text.strip();
    }
}
